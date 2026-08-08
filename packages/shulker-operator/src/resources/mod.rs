use thiserror::Error;

pub mod http_credentials;
pub mod maven;
pub mod resourceref;
pub mod resourceref_resolver;

/// Separator between a URL and its expected digest inside the `*_URLS`
/// environment variables consumed by the init scripts.
///
/// `|` cannot appear unencoded in a URL, so it is unambiguous.
pub const INTEGRITY_SEPARATOR: char = '|';

/// A resolved download, optionally carrying the digest the init container must
/// verify it against.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResolvedResource {
    pub url: url::Url,
    pub sha256: Option<String>,
}

impl ResolvedResource {
    pub fn new(url: url::Url, sha256: Option<String>) -> Self {
        ResolvedResource { url, sha256 }
    }

    /// Serialises to `<url>` or `<url>|<sha256>` for the init container.
    pub fn to_env_value(&self) -> String {
        match &self.sha256 {
            Some(sha256) => format!("{}{}{}", self.url, INTEGRITY_SEPARATOR, sha256),
            None => self.url.to_string(),
        }
    }
}

#[derive(Error, Debug)]
pub enum ResourceRefError {
    #[error("failed to resolve Kubernetes Secret: {0}")]
    FailedToResolveSecret(#[source] kube::Error),

    #[error("invalid Kubernetes Secret content: {0}")]
    InvalidSecretSpec(String),

    #[error("invalid generated URL for a resource: {0}")]
    InvalidUrlSpec(#[source] url::ParseError),

    #[error("failed to resolve Maven metadata: {0}")]
    FailedToResolveMavenMetadata(#[source] maven::resolver::ResolverError),

    #[error("invalid resource ref")]
    InvalidSpec,
}

pub type Result<T, E = ResourceRefError> = std::result::Result<T, E>;

#[cfg(test)]
mod tests {
    use super::ResolvedResource;

    #[test]
    fn to_env_value_without_a_digest_is_just_the_url() {
        let resource = ResolvedResource::new(
            url::Url::parse("https://example.com/plugin.jar").unwrap(),
            None,
        );

        assert_eq!(resource.to_env_value(), "https://example.com/plugin.jar");
    }

    #[test]
    fn to_env_value_appends_the_digest() {
        let digest = "a".repeat(64);
        let resource = ResolvedResource::new(
            url::Url::parse("https://example.com/plugin.jar").unwrap(),
            Some(digest.clone()),
        );

        assert_eq!(
            resource.to_env_value(),
            format!("https://example.com/plugin.jar|{digest}")
        );
    }

    #[test]
    fn env_value_round_trips_the_way_the_init_script_splits_it() {
        let digest = "b".repeat(64);
        let resource = ResolvedResource::new(
            url::Url::parse("https://user:pass@example.com/a/b/plugin.jar?v=1").unwrap(),
            Some(digest.clone()),
        );

        // The init script does `${spec%%|*}` and `${spec#*|}`; a URL cannot
        // contain an unencoded `|`, so exactly one separator must be present.
        let value = resource.to_env_value();
        assert_eq!(value.matches(super::INTEGRITY_SEPARATOR).count(), 1);

        let (url, sha256) = value.split_once(super::INTEGRITY_SEPARATOR).unwrap();
        assert_eq!(url, resource.url.as_str());
        assert_eq!(sha256, digest);
    }
}
