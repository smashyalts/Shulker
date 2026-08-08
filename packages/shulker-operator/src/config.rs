use std::sync::OnceLock;

/// Container images the operator injects into the workloads it creates.
///
/// These were previously hardcoded string constants, including a bare
/// `alpine:latest` for the init container. A mutable tag makes builds
/// irreproducible and is unusable in an air-gapped or registry-mirrored
/// cluster, which is the common case for self-hosted k3s/k0s installs.
///
/// The values are resolved once at process startup from CLI flags/environment
/// and are immutable afterwards, so they behave like the constants they
/// replace. Anything that reads them before [`Images::init`] runs -- unit tests,
/// in particular -- transparently gets the pinned defaults.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Images {
    /// Image used by the `init-fs` init container. Only needs a POSIX shell,
    /// `wget`, `tar`, `base64` and `sha256sum`.
    pub init: String,
    /// Image running the proxy (Velocity/BungeeCord).
    pub proxy: String,
    /// Image running the Minecraft server.
    pub minecraft_server: String,
}

// renovate: datasource=docker depName=alpine
pub const DEFAULT_INIT_IMAGE: &str = "alpine:3.21";
// renovate: datasource=docker depName=itzg/mc-proxy
pub const DEFAULT_PROXY_IMAGE: &str = "itzg/mc-proxy:2025.1.0-java21";
// renovate: datasource=docker depName=itzg/minecraft-server
pub const DEFAULT_MINECRAFT_SERVER_IMAGE: &str = "itzg/minecraft-server:2025.1.0-java21";

impl Default for Images {
    fn default() -> Self {
        Images {
            init: DEFAULT_INIT_IMAGE.to_string(),
            proxy: DEFAULT_PROXY_IMAGE.to_string(),
            minecraft_server: DEFAULT_MINECRAFT_SERVER_IMAGE.to_string(),
        }
    }
}

static IMAGES: OnceLock<Images> = OnceLock::new();

impl Images {
    /// Installs the process-wide image configuration. Called once from `main`.
    /// Returns `Err` with the already-installed configuration if called twice.
    pub fn init(images: Images) -> Result<(), Images> {
        IMAGES.set(images)
    }

    /// The process-wide image configuration, falling back to the pinned
    /// defaults if [`Images::init`] has not run.
    pub fn get() -> &'static Images {
        IMAGES.get_or_init(Images::default)
    }
}

#[cfg(test)]
mod tests {
    use super::Images;

    #[test]
    fn defaults_are_pinned_to_an_immutable_tag() {
        let images = Images::default();

        // A floating tag here would silently change what every managed Pod
        // runs, which is exactly the regression this guards against.
        assert!(!images.init.ends_with(":latest"));
        assert!(!images.proxy.ends_with(":latest"));
        assert!(!images.minecraft_server.ends_with(":latest"));
    }

    #[test]
    fn defaults_are_fully_qualified_with_a_tag_or_digest() {
        let images = Images::default();

        for image in [&images.init, &images.proxy, &images.minecraft_server] {
            assert!(
                image.contains(':') || image.contains('@'),
                "{image} has no tag or digest"
            );
        }
    }
}
