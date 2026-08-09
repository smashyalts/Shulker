use std::{collections::BTreeMap, sync::Arc};

use futures::StreamExt;
use google_agones_crds::v1::{fleet::Fleet, fleet_autoscaler::FleetAutoscaler};
use k8s_openapi::api::core::v1::ConfigMap;
use kube::{
    Api, Client, ResourceExt,
    api::{ListParams, PatchParams},
    runtime::{Controller, controller::Action},
};
use shulker_kube_utils::reconcilers::{
    backoff::FailureTracker, builder::reconcile_builder, metrics::ReconcileMetrics,
    status::patch_status,
};
use tracing::*;

use shulker_crds::{
    condition::{ConditionStatus, HasConditions},
    v1alpha1::minecraft_server_fleet::{MinecraftServerFleet, MinecraftServerFleetStatus},
};

use crate::agent::AgentConfig;

use self::{
    config_map::ConfigMapBuilder,
    fleet::{FleetBuilder, FleetBuilderContext},
    fleet_autoscaler::FleetAutoscalerBuilder,
};

use super::{ReconcilerError, Result, cluster_ref::resolve_cluster_ref};

mod config_map;
mod fleet;
mod fleet_autoscaler;

#[cfg(test)]
mod fixtures;

const CONTROLLER_NAME: &str = "minecraftserverfleet";
static METRICS: ReconcileMetrics = ReconcileMetrics::new(CONTROLLER_NAME);

struct MinecraftServerFleetReconciler {
    client: kube::Client,
    agent_config: AgentConfig,
    failures: FailureTracker,

    // Builders
    config_map_builder: ConfigMapBuilder,
    fleet_builder: FleetBuilder,
    fleet_autoscaler_builder: FleetAutoscalerBuilder,
}

impl MinecraftServerFleetReconciler {
    async fn reconcile(
        &self,
        api: Api<MinecraftServerFleet>,
        minecraft_server_fleet: Arc<MinecraftServerFleet>,
    ) -> Result<Action> {
        let cluster = resolve_cluster_ref(
            &self.client,
            &minecraft_server_fleet.namespace().unwrap(),
            &minecraft_server_fleet.spec.cluster_ref,
        )
        .await?;

        reconcile_builder(
            &self.config_map_builder,
            minecraft_server_fleet.as_ref(),
            None,
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;
        let fleet = reconcile_builder(
            &self.fleet_builder,
            minecraft_server_fleet.as_ref(),
            Some(FleetBuilderContext {
                cluster: &cluster,
                agent_config: &self.agent_config,
            }),
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(
            &self.fleet_autoscaler_builder,
            minecraft_server_fleet.as_ref(),
            None,
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;

        if let Some(fleet) = &fleet {
            if let Some(fleet_status) = &fleet.status {
                let mut minecraft_server_fleet = minecraft_server_fleet.as_ref().clone();
                if minecraft_server_fleet.status.is_none() {
                    minecraft_server_fleet.status = Some(MinecraftServerFleetStatus::default());
                }

                let status = minecraft_server_fleet.status.as_mut().unwrap();

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
                    &minecraft_server_fleet,
                )
                .await
                .map_err(ReconcilerError::BuilderError)?;
            }
        }

        Ok(Action::requeue(super::success_requeue_delay()))
    }

    async fn cleanup(&self, minecraft_server_fleet: Arc<MinecraftServerFleet>) -> Result<Action> {
        info!(
            name = minecraft_server_fleet.name_any(),
            namespace = minecraft_server_fleet.namespace(),
            "cleaning up MinecraftServerFleet",
        );

        Ok(Action::await_change())
    }

    fn get_labels(
        minecraft_server_fleet: &MinecraftServerFleet,
        name: String,
        component: String,
    ) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("app.kubernetes.io/name".to_string(), name.clone()),
            (
                "app.kubernetes.io/instance".to_string(),
                format!("{}-{}", name, minecraft_server_fleet.name_any()),
            ),
            ("app.kubernetes.io/component".to_string(), component),
            (
                "app.kubernetes.io/part-of".to_string(),
                format!(
                    "cluster-{}",
                    minecraft_server_fleet.spec.cluster_ref.name.clone()
                ),
            ),
            (
                "app.kubernetes.io/managed-by".to_string(),
                "shulker-operator".to_string(),
            ),
            (
                "minecraftcluster.shulkermc.io/name".to_string(),
                minecraft_server_fleet.spec.cluster_ref.name.clone(),
            ),
            (
                "minecraftserverfleet.shulkermc.io/name".to_string(),
                minecraft_server_fleet.name_any(),
            ),
        ])
    }
}

#[instrument(skip(ctx, minecraft_server_fleet))]
async fn reconcile(
    minecraft_server_fleet: Arc<MinecraftServerFleet>,
    ctx: Arc<MinecraftServerFleetReconciler>,
) -> Result<Action> {
    let ns = minecraft_server_fleet.namespace().unwrap();
    let minecraft_server_fleets_api: Api<MinecraftServerFleet> =
        Api::namespaced(ctx.client.clone(), &ns);

    info!(
        name = minecraft_server_fleet.name_any(),
        namespace = ns,
        "reconciling MinecraftServerFleet",
    );

    let timer = METRICS.start();
    let result = if minecraft_server_fleet.metadata.deletion_timestamp.is_none() {
        ctx.reconcile(
            minecraft_server_fleets_api.clone(),
            minecraft_server_fleet.clone(),
        )
        .await
    } else {
        ctx.cleanup(minecraft_server_fleet.clone()).await
    };

    match &result {
        Ok(_) => {
            timer.success();
            ctx.failures
                .record_success(&object_key(&minecraft_server_fleet));
            METRICS.set_failing_objects(ctx.failures.failing_count());
        }
        Err(error) => timer.failure(error.kind()),
    }

    result
}

fn object_key(minecraft_server_fleet: &MinecraftServerFleet) -> String {
    format!(
        "{}/{}",
        minecraft_server_fleet.namespace().unwrap_or_default(),
        minecraft_server_fleet.name_any()
    )
}

fn error_policy(
    minecraft_server_fleet: Arc<MinecraftServerFleet>,
    error: &ReconcilerError,
    ctx: Arc<MinecraftServerFleetReconciler>,
) -> Action {
    let key = object_key(&minecraft_server_fleet);

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
    let minecraft_server_fleets_api = Api::<MinecraftServerFleet>::all(client.clone());
    if let Err(e) = minecraft_server_fleets_api
        .list(&ListParams::default().limit(1))
        .await
    {
        error!("CRD is not queryable; {e:?}. Is the CRD installed?");
        std::process::exit(1);
    }

    let context = MinecraftServerFleetReconciler {
        client: client.clone(),
        agent_config,
        failures: FailureTracker::new(),
        config_map_builder: ConfigMapBuilder::new(client.clone()),
        fleet_builder: FleetBuilder::new(client.clone()),
        fleet_autoscaler_builder: FleetAutoscalerBuilder::new(client.clone()),
    };

    Controller::new(minecraft_server_fleets_api, super::owner_watcher_config())
        .owns(
            Api::<ConfigMap>::all(client.clone()),
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
