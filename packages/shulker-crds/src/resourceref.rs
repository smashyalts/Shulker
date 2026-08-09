use kube::KubeSchema;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize, Clone, Debug, Default, KubeSchema)]
#[serde(rename_all = "camelCase")]
// Rejected at admission instead of failing the reconcile with `InvalidSpec`
// long after `kubectl apply` reported success.
#[x_kube(validation = "has(self.url) != has(self.urlFrom)")]
pub struct ResourceRefSpec {
    pub url: Option<String>,
    pub url_from: Option<ResourceRefFromSpec>,

    /// Expected SHA-256 digest of the referenced file, as 64 lowercase hex
    /// characters.
    ///
    /// When set, the init container verifies the download against this digest
    /// and refuses to start the server if it does not match. Strongly
    /// recommended: without it, anything served at the URL is executed inside
    /// the server Pod, so a compromised or hijacked artifact host is enough to
    /// run arbitrary code in the cluster.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    #[schemars(regex(pattern = r"^[a-f0-9]{64}$"))]
    pub sha256: Option<String>,
}

#[derive(Deserialize, Serialize, Clone, Debug, Default, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ResourceRefFromSpec {
    pub maven_ref: Option<ResourceRefFromMavenSpec>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct ResourceRefFromMavenSpec {
    pub repository_url: String,
    pub group_id: String,
    pub artifact_id: String,
    pub version: String,
    pub classifier: Option<String>,
    pub credentials_secret_name: Option<String>,
}
