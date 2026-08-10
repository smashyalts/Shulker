#[cfg(any(test, debug_assertions))]
pub const SHULKER_PLUGIN_REPOSITORY: &str =
    "https://maven.jeremylvln.fr/repository/shulker-snapshots";
#[cfg(not(debug_assertions))]
pub const SHULKER_PLUGIN_REPOSITORY: &str =
    "https://maven.jeremylvln.fr/repository/shulker-releases";

#[cfg(test)]
pub const SHULKER_PLUGIN_VERSION: &str = "0.0.0-test-cfg";
#[cfg(all(not(test), debug_assertions))]
pub const SHULKER_PLUGIN_VERSION: &str =
    const_format::concatcp!(env!("CARGO_PKG_VERSION"), "-SNAPSHOT");
#[cfg(not(debug_assertions))]
pub const SHULKER_PLUGIN_VERSION: &str = env!("CARGO_PKG_VERSION");

// Container images moved to `crate::config::Images` so they can be overridden
// at startup for private registries and air-gapped clusters.

/// Name of the Agones counter the server agent keeps in step with the live
/// player count.
///
/// Declared on every GameServer the operator builds, so a Counter fleet
/// autoscaler can scale on real free slots rather than on whole servers being
/// Ready or Allocated.
pub const PLAYERS_COUNTER: &str = "players";
