use std::sync::LazyLock;
use std::time::Instant;

use prometheus::{
    HistogramVec, IntCounterVec, IntGaugeVec, register_histogram_vec, register_int_counter_vec,
    register_int_gauge_vec,
};

/// Reconciliation metrics for every controller in the operator.
///
/// The operator already served a `/metrics` endpoint, but the only call site
/// that would have populated reconcile data was commented out in each
/// controller's `error_policy`, so failures were invisible to monitoring. These
/// are registered against the default Prometheus registry that the metrics
/// endpoint gathers from.
static RECONCILE_TOTAL: LazyLock<IntCounterVec> = LazyLock::new(|| {
    register_int_counter_vec!(
        "shulker_reconcile_total",
        "Total number of reconciliations, by controller and outcome",
        &["controller", "result"]
    )
    .expect("shulker_reconcile_total is registered exactly once")
});

static RECONCILE_ERRORS_TOTAL: LazyLock<IntCounterVec> = LazyLock::new(|| {
    register_int_counter_vec!(
        "shulker_reconcile_errors_total",
        "Total number of failed reconciliations, by controller and error kind",
        &["controller", "reason"]
    )
    .expect("shulker_reconcile_errors_total is registered exactly once")
});

static RECONCILE_DURATION: LazyLock<HistogramVec> = LazyLock::new(|| {
    register_histogram_vec!(
        "shulker_reconcile_duration_seconds",
        "Duration of reconciliations, by controller",
        &["controller"],
        vec![0.01, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0]
    )
    .expect("shulker_reconcile_duration_seconds is registered exactly once")
});

static OBJECTS_FAILING: LazyLock<IntGaugeVec> = LazyLock::new(|| {
    register_int_gauge_vec!(
        "shulker_reconcile_failing_objects",
        "Number of objects currently in a failing reconcile state, by controller",
        &["controller"]
    )
    .expect("shulker_reconcile_failing_objects is registered exactly once")
});

/// Records the outcome and duration of a single reconcile pass.
pub struct ReconcileMetrics {
    controller: &'static str,
}

impl ReconcileMetrics {
    pub const fn new(controller: &'static str) -> Self {
        ReconcileMetrics { controller }
    }

    /// Starts timing a reconcile. Call [`ReconcileTimer::success`] or
    /// [`ReconcileTimer::failure`] on the returned value.
    pub fn start(&self) -> ReconcileTimer {
        ReconcileTimer {
            controller: self.controller,
            started_at: Instant::now(),
        }
    }

    /// Publishes how many objects the controller currently cannot reconcile.
    pub fn set_failing_objects(&self, count: usize) {
        OBJECTS_FAILING
            .with_label_values(&[self.controller])
            .set(count as i64);
    }
}

#[must_use = "a started reconcile timer must be resolved with success() or failure()"]
pub struct ReconcileTimer {
    controller: &'static str,
    started_at: Instant,
}

impl ReconcileTimer {
    pub fn success(self) {
        self.observe();
        RECONCILE_TOTAL
            .with_label_values(&[self.controller, "success"])
            .inc();
    }

    /// `reason` should be a low-cardinality error kind, never a formatted
    /// message -- it becomes a Prometheus label value.
    pub fn failure(self, reason: &str) {
        self.observe();
        RECONCILE_TOTAL
            .with_label_values(&[self.controller, "failure"])
            .inc();
        RECONCILE_ERRORS_TOTAL
            .with_label_values(&[self.controller, reason])
            .inc();
    }

    fn observe(&self) {
        RECONCILE_DURATION
            .with_label_values(&[self.controller])
            .observe(self.started_at.elapsed().as_secs_f64());
    }
}

#[cfg(test)]
mod tests {
    use super::{RECONCILE_ERRORS_TOTAL, RECONCILE_TOTAL, ReconcileMetrics};

    #[test]
    fn success_and_failure_are_counted_separately() {
        let metrics = ReconcileMetrics::new("test-controller-counts");

        let before_success = RECONCILE_TOTAL
            .with_label_values(&["test-controller-counts", "success"])
            .get();

        metrics.start().success();
        metrics.start().failure("BuilderError");

        assert_eq!(
            RECONCILE_TOTAL
                .with_label_values(&["test-controller-counts", "success"])
                .get(),
            before_success + 1
        );
        assert_eq!(
            RECONCILE_TOTAL
                .with_label_values(&["test-controller-counts", "failure"])
                .get(),
            1
        );
        assert_eq!(
            RECONCILE_ERRORS_TOTAL
                .with_label_values(&["test-controller-counts", "BuilderError"])
                .get(),
            1
        );
    }

    #[test]
    fn metrics_are_exported_through_the_default_registry() {
        let metrics = ReconcileMetrics::new("test-controller-registry");
        metrics.start().success();
        metrics.set_failing_objects(3);

        let gathered = prometheus::gather();
        let names: Vec<_> = gathered.iter().map(|f| f.name()).collect();

        assert!(names.contains(&"shulker_reconcile_total"));
        assert!(names.contains(&"shulker_reconcile_duration_seconds"));
        assert!(names.contains(&"shulker_reconcile_failing_objects"));
    }
}
