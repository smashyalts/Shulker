use std::{
    collections::HashMap,
    sync::Mutex,
    time::{Duration, SystemTime},
};

use rand::RngExt;

/// Applies symmetric random jitter to a duration.
///
/// Controllers requeue on fixed intervals, so every object created in the same
/// batch -- an entire fleet scaling up, or every object rediscovered after an
/// operator restart -- lands on the same schedule and hits the API server in
/// lockstep forever. Spreading each requeue over a window breaks that
/// synchronisation.
///
/// `ratio` is the fraction of `base` to spread over, clamped to `0.0..=1.0`.
/// A ratio of `0.2` yields a duration uniformly in `[0.8 * base, 1.2 * base]`.
pub fn jittered(base: Duration, ratio: f64) -> Duration {
    let ratio = ratio.clamp(0.0, 1.0);
    if ratio == 0.0 {
        return base;
    }

    let base_secs = base.as_secs_f64();
    let spread = base_secs * ratio;
    let jittered = rand::rng().random_range((base_secs - spread)..=(base_secs + spread));

    Duration::from_secs_f64(jittered.max(0.0))
}

/// Exponential backoff with jitter, saturating at `max`.
///
/// `attempt` is the number of consecutive failures so far, counting from 1.
pub fn exponential(attempt: u32, base: Duration, max: Duration) -> Duration {
    let exponent = attempt.saturating_sub(1).min(16);
    let scaled = base
        .checked_mul(2_u32.saturating_pow(exponent))
        .unwrap_or(max)
        .min(max);

    jittered(scaled, 0.2).min(max)
}

/// Tracks consecutive reconcile failures per object so that a persistently
/// failing resource backs off instead of retrying at a flat interval forever.
///
/// Entries are dropped as soon as an object reconciles successfully, so this
/// only ever holds objects that are currently failing.
#[derive(Debug, Default)]
pub struct FailureTracker {
    failures: Mutex<HashMap<String, (u32, SystemTime)>>,
}

impl FailureTracker {
    pub fn new() -> Self {
        FailureTracker::default()
    }

    /// Records a failure for `key` and returns how long to wait before retrying.
    pub fn record_failure(&self, key: String, base: Duration, max: Duration) -> Duration {
        let attempt = {
            let mut failures = self.failures.lock().unwrap_or_else(|e| e.into_inner());
            let entry = failures.entry(key).or_insert((0, SystemTime::now()));
            entry.0 = entry.0.saturating_add(1);
            entry.1 = SystemTime::now();
            entry.0
        };

        exponential(attempt, base, max)
    }

    /// Clears the failure streak for `key` after a successful reconcile.
    pub fn record_success(&self, key: &str) {
        self.failures
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(key);
    }

    /// Number of objects currently in a failing state.
    pub fn failing_count(&self) -> usize {
        self.failures
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .len()
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{exponential, jittered, FailureTracker};

    #[test]
    fn jittered_stays_within_the_requested_window() {
        let base = Duration::from_secs(100);

        for _ in 0..1_000 {
            let value = jittered(base, 0.2);
            assert!(value >= Duration::from_secs(80), "{value:?} below window");
            assert!(value <= Duration::from_secs(120), "{value:?} above window");
        }
    }

    #[test]
    fn jittered_with_zero_ratio_is_the_identity() {
        let base = Duration::from_secs(42);
        assert_eq!(jittered(base, 0.0), base);
    }

    #[test]
    fn jittered_spreads_values_apart() {
        let base = Duration::from_secs(100);
        let samples: Vec<_> = (0..100).map(|_| jittered(base, 0.5)).collect();
        let distinct = samples.iter().collect::<std::collections::HashSet<_>>();

        // The whole point is that co-scheduled objects stop sharing a deadline.
        assert!(
            distinct.len() > 50,
            "only {} distinct values",
            distinct.len()
        );
    }

    #[test]
    fn exponential_grows_then_saturates() {
        let base = Duration::from_secs(5);
        let max = Duration::from_secs(300);

        // Compare against the un-jittered midpoint of each window.
        assert!(exponential(1, base, max) <= Duration::from_secs(6));
        assert!(exponential(2, base, max) >= Duration::from_secs(8));
        assert!(exponential(3, base, max) >= Duration::from_secs(16));

        for attempt in 10..40 {
            assert!(exponential(attempt, base, max) <= max);
        }
    }

    #[test]
    fn exponential_does_not_overflow_on_absurd_attempt_counts() {
        let value = exponential(u32::MAX, Duration::from_secs(5), Duration::from_secs(300));
        assert!(value <= Duration::from_secs(300));
    }

    #[test]
    fn tracker_escalates_per_object_and_resets_on_success() {
        let tracker = FailureTracker::new();
        let base = Duration::from_secs(5);
        let max = Duration::from_secs(600);

        let first = tracker.record_failure("ns/a".to_string(), base, max);
        let second = tracker.record_failure("ns/a".to_string(), base, max);
        assert!(second > first, "{second:?} did not grow from {first:?}");

        // A different object has its own streak.
        let other = tracker.record_failure("ns/b".to_string(), base, max);
        assert!(other <= Duration::from_secs(6));
        assert_eq!(tracker.failing_count(), 2);

        tracker.record_success("ns/a");
        assert_eq!(tracker.failing_count(), 1);

        let after_reset = tracker.record_failure("ns/a".to_string(), base, max);
        assert!(after_reset <= Duration::from_secs(6));
    }
}
