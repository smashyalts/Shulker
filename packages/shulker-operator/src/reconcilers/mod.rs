use std::time::Duration;

use kube::runtime::watcher::Config;
use thiserror::Error;

mod agent;
mod cluster_ref;
pub mod minecraft_cluster;
pub mod minecraft_server;
pub mod minecraft_server_fleet;
pub mod proxy_fleet;
mod redis_ref;

/// Label every resource the operator creates carries, via each reconciler's
/// `get_labels`.
pub const MANAGED_BY_LABEL: &str = "app.kubernetes.io/managed-by";
pub const MANAGED_BY_VALUE: &str = "shulker-operator";

/// Base delay before retrying a failed reconcile.
pub const ERROR_REQUEUE_BASE: Duration = Duration::from_secs(5);
/// Ceiling for the retry delay of a persistently failing object.
pub const ERROR_REQUEUE_MAX: Duration = Duration::from_secs(10 * 60);
/// Nominal interval at which a healthy object is re-reconciled.
pub const SUCCESS_REQUEUE_INTERVAL: Duration = Duration::from_secs(5 * 60);
/// Fraction of [`SUCCESS_REQUEUE_INTERVAL`] to spread requeues over.
pub const SUCCESS_REQUEUE_JITTER: f64 = 0.1;

/// Watcher configuration for the primary custom resource of a controller.
pub fn owner_watcher_config() -> Config {
    Config::default().any_semantic()
}

/// Watcher configuration for resources the operator owns.
///
/// Controllers used to watch owned kinds unfiltered and cluster-wide, so every
/// controller streamed every ConfigMap, Secret, Service, Role, RoleBinding,
/// ServiceAccount, StatefulSet and Agones object in the cluster into memory --
/// including all the ones belonging to unrelated workloads. Restricting the
/// watch to objects the operator itself labelled cuts that to the objects the
/// controller can actually act on.
pub fn owned_watcher_config() -> Config {
    Config::default()
        .labels(&format!("{MANAGED_BY_LABEL}={MANAGED_BY_VALUE}"))
        .any_semantic()
}

/// Requeue delay for a successful reconcile, jittered so that objects created
/// together do not stay in lockstep for the lifetime of the cluster.
pub fn success_requeue_delay() -> Duration {
    shulker_kube_utils::reconcilers::backoff::jittered(
        SUCCESS_REQUEUE_INTERVAL,
        SUCCESS_REQUEUE_JITTER,
    )
}

#[derive(Error, Debug)]
pub enum ReconcilerError {
    #[error("failed to reconcile resource: {0}")]
    FinalizerError(#[source] Box<kube::runtime::finalizer::Error<ReconcilerError>>),

    #[error("failed to resolve cluster ref: {1}")]
    InvalidClusterRef(String, #[source] kube::Error),

    #[error("failed to build resource: {0}")]
    BuilderError(#[source] shulker_kube_utils::reconcilers::BuilderReconcilerError),

    #[error("failed to delete stale resource: {0}")]
    FailedToDeleteStale(#[source] kube::Error),
}

impl ReconcilerError {
    /// Stable, low-cardinality discriminant for use as a metric label.
    pub fn kind(&self) -> &'static str {
        match self {
            ReconcilerError::FinalizerError(_) => "FinalizerError",
            ReconcilerError::InvalidClusterRef(_, _) => "InvalidClusterRef",
            ReconcilerError::BuilderError(_) => "BuilderError",
            ReconcilerError::FailedToDeleteStale(_) => "FailedToDeleteStale",
        }
    }
}

pub type Result<T, E = ReconcilerError> = std::result::Result<T, E>;

#[cfg(test)]
mod tests {
    use super::{
        ERROR_REQUEUE_BASE, ERROR_REQUEUE_MAX, ReconcilerError, SUCCESS_REQUEUE_INTERVAL,
        SUCCESS_REQUEUE_JITTER, success_requeue_delay,
    };

    #[test]
    fn error_kinds_are_stable_and_distinct() {
        let kinds = [
            ReconcilerError::InvalidClusterRef(
                "x".to_string(),
                kube::Error::LinesCodecMaxLineLengthExceeded,
            )
            .kind(),
            ReconcilerError::FailedToDeleteStale(kube::Error::LinesCodecMaxLineLengthExceeded)
                .kind(),
        ];

        assert_eq!(kinds[0], "InvalidClusterRef");
        assert_eq!(kinds[1], "FailedToDeleteStale");
    }

    #[test]
    fn success_requeue_is_jittered_around_the_nominal_interval() {
        let lower = SUCCESS_REQUEUE_INTERVAL.mul_f64(1.0 - SUCCESS_REQUEUE_JITTER);
        let upper = SUCCESS_REQUEUE_INTERVAL.mul_f64(1.0 + SUCCESS_REQUEUE_JITTER);

        let mut seen = std::collections::HashSet::new();
        for _ in 0..200 {
            let delay = success_requeue_delay();
            assert!(delay >= lower && delay <= upper, "{delay:?} out of window");
            seen.insert(delay);
        }

        assert!(seen.len() > 100, "requeue delay is not actually spread out");
    }

    #[test]
    fn error_requeue_bounds_are_sane() {
        assert!(ERROR_REQUEUE_BASE < ERROR_REQUEUE_MAX);
    }
}
