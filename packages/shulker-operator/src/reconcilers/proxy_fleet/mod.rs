use std::{collections::BTreeMap, sync::Arc};

use futures::StreamExt;
use google_agones_crds::v1::{fleet::Fleet, fleet_autoscaler::FleetAutoscaler};
use k8s_openapi::api::core::v1::{ConfigMap, Service};
use kube::{
    api::{ListParams, PatchParams},
    runtime::{controller::Action, Controller},
    Api, Client, ResourceExt,
};
use shulker_kube_utils::reconcilers::{
    backoff::FailureTracker, builder::reconcile_builder, metrics::ReconcileMetrics,
    status::patch_status,
};
use tracing::*;

use shulker_crds::{
    condition::{ConditionStatus, HasConditions},
    v1alpha1::proxy_fleet::{ProxyFleet, ProxyFleetStatus},
};

use crate::agent::AgentConfig;

use self::{
    config_map::ConfigMapBuilder,
    fleet::{FleetBuilder, FleetBuilderContext},
    fleet_autoscaler::FleetAutoscalerBuilder,
    service::ServiceBuilder,
};

use super::{cluster_ref::resolve_cluster_ref, ReconcilerError, Result};

mod config_map;
mod fleet;
mod fleet_autoscaler;
mod service;

#[cfg(test)]
mod fixtures;

const CONTROLLER_NAME: &str = "proxyfleet";
static METRICS: ReconcileMetrics = ReconcileMetrics::new(CONTROLLER_NAME);

struct ProxyFleetReconciler {
    client: kube::Client,
    agent_config: AgentConfig,
    failures: FailureTracker,

    // Builders
    config_map_builder: ConfigMapBuilder,
    service_builder: ServiceBuilder,
    fleet_builder: FleetBuilder,
    fleet_autoscaler_builder: FleetAutoscalerBuilder,
}

impl ProxyFleetReconciler {
    async fn reconcile(
        &self,
        api: Api<ProxyFleet>,
        proxy_fleet: Arc<ProxyFleet>,
    ) -> Result<Action> {
        let cluster = resolve_cluster_ref(
            &self.client,
            &proxy_fleet.namespace().unwrap(),
            &proxy_fleet.spec.cluster_ref,
        )
        .await?;

        reconcile_builder(&self.config_map_builder, proxy_fleet.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.service_builder, proxy_fleet.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        let fleet = reconcile_builder(
            &self.fleet_builder,
            proxy_fleet.as_ref(),
            Some(FleetBuilderContext {
                cluster: &cluster,
                agent_config: &self.agent_config,
            }),
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.fleet_autoscaler_builder, proxy_fleet.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;

        if let Some(fleet) = &fleet {
            if let Some(fleet_status) = &fleet.status {
                let mut proxy_fleet = proxy_fleet.as_ref().clone();
                if proxy_fleet.status.is_none() {
                    proxy_fleet.status = Some(ProxyFleetStatus::default());
                }

                let status = proxy_fleet.status.as_mut().unwrap();

                status.replicas = fleet_status.replicas;
                status.ready_replicas = fleet_status.ready_replicas;
                status.allocated_replicas = fleet_status.allocated_replicas;

                if status.ready_replicas > 0 || status.allocated_replicas > 0 {
                    status.set_condition(
                        "Available".to_string(),
                        ConditionStatus::True,
                        "AtLeastOneReadyOrAllocated".to_string(),
                        "One or more servers are ready or allocated".to_string(),
                    );
                } else {
                    status.set_condition(
                        "Available".to_string(),
                        ConditionStatus::False,
                        "NoneReady".to_string(),
                        "No server are ready".to_string(),
                    );
                };

                patch_status(
                    &api,
                    &PatchParams::apply("shulker-operator").force(),
                    &proxy_fleet,
                )
                .await
                .map_err(ReconcilerError::BuilderError)?;
            }
        }

        Ok(Action::requeue(super::success_requeue_delay()))
    }

    async fn cleanup(&self, proxy_fleet: Arc<ProxyFleet>) -> Result<Action> {
        info!(
            name = proxy_fleet.name_any(),
            namespace = proxy_fleet.namespace(),
            "cleaning up ProxyFleet",
        );

        Ok(Action::await_change())
    }

    fn get_labels(
        proxy_fleet: &ProxyFleet,
        name: String,
        component: String,
    ) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("app.kubernetes.io/name".to_string(), name.clone()),
            (
                "app.kubernetes.io/instance".to_string(),
                format!("{}-{}", name, proxy_fleet.name_any()),
            ),
            ("app.kubernetes.io/component".to_string(), component),
            (
                "app.kubernetes.io/part-of".to_string(),
                format!("cluster-{}", proxy_fleet.spec.cluster_ref.name.clone()),
            ),
            (
                "app.kubernetes.io/managed-by".to_string(),
                "shulker-operator".to_string(),
            ),
            (
                "minecraftcluster.shulkermc.io/name".to_string(),
                proxy_fleet.spec.cluster_ref.name.clone(),
            ),
            (
                "proxyfleet.shulkermc.io/name".to_string(),
                proxy_fleet.name_any(),
            ),
        ])
    }
}

#[instrument(skip(ctx, proxy_fleet))]
async fn reconcile(proxy_fleet: Arc<ProxyFleet>, ctx: Arc<ProxyFleetReconciler>) -> Result<Action> {
    let ns = proxy_fleet.namespace().unwrap();
    let proxy_fleets_api: Api<ProxyFleet> = Api::namespaced(ctx.client.clone(), &ns);

    info!(
        name = proxy_fleet.name_any(),
        namespace = ns,
        "reconciling ProxyFleet",
    );

    let timer = METRICS.start();
    let result = if proxy_fleet.metadata.deletion_timestamp.is_none() {
        ctx.reconcile(proxy_fleets_api.clone(), proxy_fleet.clone())
            .await
    } else {
        ctx.cleanup(proxy_fleet.clone()).await
    };

    match &result {
        Ok(_) => {
            timer.success();
            ctx.failures.record_success(&object_key(&proxy_fleet));
            METRICS.set_failing_objects(ctx.failures.failing_count());
        }
        Err(error) => timer.failure(error.kind()),
    }

    result
}

fn object_key(proxy_fleet: &ProxyFleet) -> String {
    format!(
        "{}/{}",
        proxy_fleet.namespace().unwrap_or_default(),
        proxy_fleet.name_any()
    )
}

fn error_policy(
    proxy_fleet: Arc<ProxyFleet>,
    error: &ReconcilerError,
    ctx: Arc<ProxyFleetReconciler>,
) -> Action {
    let key = object_key(&proxy_fleet);

    // Retrying every 5s forever meant a permanently broken object hammered the
    // API server indefinitely, and every object broken by the same cause
    // retried in lockstep. Back off per object instead.
    let delay = ctx.failures.record_failure(
        key.clone(),
        super::ERROR_REQUEUE_BASE,
        super::ERROR_REQUEUE_MAX,
    );
    METRICS.set_failing_objects(ctx.failures.failing_count());

    warn!(
        object = key,
        retry_in_secs = delay.as_secs(),
        "reconcile failed: {:?}",
        error
    );

    Action::requeue(delay)
}

pub async fn run(client: Client, agent_config: AgentConfig) {
    let proxy_fleets_api = Api::<ProxyFleet>::all(client.clone());
    if let Err(e) = proxy_fleets_api.list(&ListParams::default().limit(1)).await {
        error!("CRD is not queryable; {e:?}. Is the CRD installed?");
        std::process::exit(1);
    }

    let context = ProxyFleetReconciler {
        client: client.clone(),
        agent_config,
        failures: FailureTracker::new(),
        config_map_builder: ConfigMapBuilder::new(client.clone()),
        service_builder: ServiceBuilder::new(client.clone()),
        fleet_builder: FleetBuilder::new(client.clone()),
        fleet_autoscaler_builder: FleetAutoscalerBuilder::new(client.clone()),
    };

    Controller::new(proxy_fleets_api, super::owner_watcher_config())
        .owns(
            Api::<ConfigMap>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<Service>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<Fleet>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<FleetAutoscaler>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .shutdown_on_signal()
        .run(reconcile, error_policy, context.into())
        .for_each(|result| {
            // Previously `filter_map(Result::ok)` discarded terminal controller
            // errors without a trace, so anything the error policy could not
            // recover from vanished silently.
            if let Err(error) = result {
                error!("controller stream reported an error: {:?}", error);
            }
            futures::future::ready(())
        })
        .await;
}
