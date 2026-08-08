use std::{collections::BTreeMap, sync::Arc};

use external_servers_config_map::ExternalServersConfigMapBuilder;
use futures::StreamExt;
use k8s_openapi::api::{
    apps::v1::StatefulSet,
    core::v1::{ConfigMap, Secret, Service, ServiceAccount},
    rbac::v1::{Role, RoleBinding},
};
use kube::{
    api::ListParams,
    runtime::{
        controller::Action,
        finalizer::{finalizer, Event as Finalizer},
        Controller,
    },
    Api, Client, ResourceExt,
};
use shulker_kube_utils::reconcilers::{
    backoff::FailureTracker, builder::reconcile_builder, metrics::ReconcileMetrics,
};
use tracing::*;

use shulker_crds::v1alpha1::minecraft_cluster::MinecraftCluster;

use crate::reconcilers::ReconcilerError;

use self::{
    forwarding_secret::ForwardingSecretBuilder, headless_service::HeadlessServiceBuilder,
    minecraft_server_role::MinecraftServerRoleBuilder,
    minecraft_server_role_binding::MinecraftServerRoleBindingBuilder,
    minecraft_server_service_account::MinecraftServerServiceAccountBuilder,
    proxy_role::ProxyRoleBuilder, proxy_role_binding::ProxyRoleBindingBuilder,
    proxy_service_account::ProxyServiceAccountBuilder, redis_service::RedisServiceBuilder,
    redis_stateful_set::RedisStatefulSetBuilder,
};

use super::Result;

pub mod external_servers_config_map;
mod forwarding_secret;
mod headless_service;
mod minecraft_server_role;
mod minecraft_server_role_binding;
mod minecraft_server_service_account;
mod proxy_role;
mod proxy_role_binding;
mod proxy_service_account;
pub mod redis_service;
mod redis_stateful_set;

#[cfg(test)]
pub mod fixtures;

static FINALIZER: &str = "minecraftclusters.shulkermc.io";

const CONTROLLER_NAME: &str = "minecraftcluster";
static METRICS: ReconcileMetrics = ReconcileMetrics::new(CONTROLLER_NAME);

struct MinecraftClusterReconciler {
    client: kube::Client,
    failures: FailureTracker,

    // Builders
    forwarding_secret_builder: ForwardingSecretBuilder,
    headless_service_builder: HeadlessServiceBuilder,
    proxy_service_account_builder: ProxyServiceAccountBuilder,
    proxy_role_builder: ProxyRoleBuilder,
    proxy_role_binding_builder: ProxyRoleBindingBuilder,
    minecraft_server_service_account_builder: MinecraftServerServiceAccountBuilder,
    minecraft_server_role_builder: MinecraftServerRoleBuilder,
    minecraft_server_role_binding_builder: MinecraftServerRoleBindingBuilder,
    redis_service_builder: RedisServiceBuilder,
    redis_stateful_set_builder: RedisStatefulSetBuilder,
    external_servers_config_map_builder: ExternalServersConfigMapBuilder,
}

impl MinecraftClusterReconciler {
    async fn reconcile(
        &self,
        _api: Api<MinecraftCluster>,
        cluster: Arc<MinecraftCluster>,
    ) -> Result<Action> {
        reconcile_builder(&self.forwarding_secret_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.headless_service_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.proxy_service_account_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.proxy_role_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.proxy_role_binding_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(
            &self.minecraft_server_service_account_builder,
            cluster.as_ref(),
            None,
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.minecraft_server_role_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(
            &self.minecraft_server_role_binding_builder,
            cluster.as_ref(),
            None,
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.redis_service_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(&self.redis_stateful_set_builder, cluster.as_ref(), None)
            .await
            .map_err(ReconcilerError::BuilderError)?;
        reconcile_builder(
            &self.external_servers_config_map_builder,
            cluster.as_ref(),
            None,
        )
        .await
        .map_err(ReconcilerError::BuilderError)?;

        Ok(Action::requeue(super::success_requeue_delay()))
    }

    async fn cleanup(&self, cluster: Arc<MinecraftCluster>) -> Result<Action> {
        info!(
            name = cluster.name_any(),
            namespace = cluster.namespace(),
            "cleaning up MinecraftCluster",
        );

        Ok(Action::await_change())
    }

    fn get_labels(
        cluster: &MinecraftCluster,
        name: String,
        component: String,
    ) -> BTreeMap<String, String> {
        BTreeMap::from([
            ("app.kubernetes.io/name".to_string(), name.clone()),
            (
                "app.kubernetes.io/instance".to_string(),
                format!("{}-{}", name, cluster.name_any()),
            ),
            ("app.kubernetes.io/component".to_string(), component),
            (
                "app.kubernetes.io/part-of".to_string(),
                format!("cluster-{}", cluster.name_any()),
            ),
            (
                "app.kubernetes.io/managed-by".to_string(),
                "shulker-operator".to_string(),
            ),
            (
                "minecraftcluster.shulkermc.io/name".to_string(),
                cluster.name_any(),
            ),
        ])
    }
}

#[instrument(skip(ctx, cluster))]
async fn reconcile(
    cluster: Arc<MinecraftCluster>,
    ctx: Arc<MinecraftClusterReconciler>,
) -> Result<Action> {
    let ns = cluster.namespace().unwrap();
    let clusters_api: Api<MinecraftCluster> = Api::namespaced(ctx.client.clone(), &ns);

    info!(
        name = cluster.name_any(),
        namespace = ns,
        "reconciling MinecraftCluster",
    );

    let key = object_key(&cluster);
    let timer = METRICS.start();

    let result = finalizer(&clusters_api, FINALIZER, cluster, |event| async {
        match event {
            Finalizer::Apply(cluster) => ctx.reconcile(clusters_api.clone(), cluster.clone()).await,
            Finalizer::Cleanup(cluster) => ctx.cleanup(cluster.clone()).await,
        }
    })
    .await
    .map_err(|e| ReconcilerError::FinalizerError(Box::new(e)));

    match &result {
        Ok(_) => {
            timer.success();
            ctx.failures.record_success(&key);
            METRICS.set_failing_objects(ctx.failures.failing_count());
        }
        Err(error) => timer.failure(error.kind()),
    }

    result
}

fn object_key(cluster: &MinecraftCluster) -> String {
    format!(
        "{}/{}",
        cluster.namespace().unwrap_or_default(),
        cluster.name_any()
    )
}

fn error_policy(
    cluster: Arc<MinecraftCluster>,
    error: &ReconcilerError,
    ctx: Arc<MinecraftClusterReconciler>,
) -> Action {
    let key = object_key(&cluster);

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

pub async fn run(client: Client) {
    let clusters_api = Api::<MinecraftCluster>::all(client.clone());
    if let Err(e) = clusters_api.list(&ListParams::default().limit(1)).await {
        error!("CRD is not queryable; {e:?}. Is the CRD installed?");
        std::process::exit(1);
    }

    let context = MinecraftClusterReconciler {
        client: client.clone(),
        failures: FailureTracker::new(),
        forwarding_secret_builder: ForwardingSecretBuilder::new(client.clone()),
        headless_service_builder: HeadlessServiceBuilder::new(client.clone()),
        proxy_service_account_builder: ProxyServiceAccountBuilder::new(client.clone()),
        proxy_role_builder: ProxyRoleBuilder::new(client.clone()),
        proxy_role_binding_builder: ProxyRoleBindingBuilder::new(client.clone()),
        minecraft_server_service_account_builder: MinecraftServerServiceAccountBuilder::new(
            client.clone(),
        ),
        minecraft_server_role_builder: MinecraftServerRoleBuilder::new(client.clone()),
        minecraft_server_role_binding_builder: MinecraftServerRoleBindingBuilder::new(
            client.clone(),
        ),
        redis_service_builder: RedisServiceBuilder::new(client.clone()),
        redis_stateful_set_builder: RedisStatefulSetBuilder::new(client.clone()),

        external_servers_config_map_builder: ExternalServersConfigMapBuilder::new(client.clone()),
    };

    Controller::new(clusters_api, super::owner_watcher_config())
        .owns(
            Api::<ConfigMap>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<Secret>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<ServiceAccount>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<Role>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<RoleBinding>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<Service>::all(client.clone()),
            super::owned_watcher_config(),
        )
        .owns(
            Api::<StatefulSet>::all(client.clone()),
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
