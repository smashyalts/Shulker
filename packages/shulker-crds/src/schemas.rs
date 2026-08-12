use google_agones_crds::v1::fleet_autoscaler::FleetAutoscalerPolicySpec;
use k8s_openapi::apimachinery::pkg::util::intstr::IntOrString;
use kube::core::ObjectMeta;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct TemplateSpec<T> {
    /// Common metadata to add to the created objects
    pub metadata: Option<ObjectMeta>,

    /// The spec of the object to create from the template
    pub spec: T,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImageOverrideSpec {
    /// Complete name of the image, including the repository name
    /// and tag
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,

    /// Policy about when to pull the image
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pull_policy: Option<String>,

    ///  A list of secrets to use to pull the image
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_pull_secrets: Option<Vec<k8s_openapi::api::core::v1::LocalObjectReference>>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalingSpec {
    pub agones_policy: Option<FleetAutoscalerPolicySpec>,
}

/// Schema for a free-form object the API server stores verbatim.
///
/// Used for the pod template overlay. Generating the full `PodTemplateSpec`
/// schema instead would inline the entire pod API -- containers, probes,
/// security contexts, every volume source -- into each of the four CRDs, and
/// the manifests are already among the largest objects the chart installs.
/// Marking it `x-kubernetes-preserve-unknown-fields` keeps the whole pod API
/// reachable without paying for the schema, at the cost of client-side
/// validation of the overlay.
pub fn preserve_unknown_fields(
    _generator: &mut schemars::r#gen::SchemaGenerator,
) -> schemars::schema::Schema {
    let mut schema = schemars::schema::SchemaObject {
        instance_type: Some(schemars::schema::InstanceType::Object.into()),
        ..Default::default()
    };

    schema.extensions.insert(
        "x-kubernetes-preserve-unknown-fields".to_string(),
        serde_json::Value::Bool(true),
    );

    schema.into()
}

/// How a fleet replaces its members when the template changes.
///
/// Agones' Fleet takes a `DeploymentStrategy`, but nests the numbers under
/// `rollingUpdate`. This is flat on purpose: a zone author writes
///
/// ```yaml
/// strategy:
///   type: RollingUpdate
///   maxSurge: 1
///   maxUnavailable: 0
/// ```
///
/// and the operator does the nesting. One less level to get wrong in a file
/// that is edited far more often than this code is.
///
/// WITHOUT THIS THE FLEET IS RECREATED. That was the previous hardcoded
/// behaviour and remains the default when the field is absent: every server is
/// deleted at once, so a plugin digest bump -- not just a version change --
/// disconnects everybody for as long as a boot takes. `maxUnavailable: 0` with
/// `maxSurge: 1` brings the replacement up BEFORE retiring an old one, so there
/// is always somewhere to send a player.
#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema)]
#[serde(rename_all = "camelCase")]
pub struct FleetStrategySpec {
    /// `RollingUpdate` or `Recreate`. Defaults to `Recreate`, which is what
    /// this operator did unconditionally before the field existed.
    #[serde(default = "FleetStrategySpec::default_type", rename = "type")]
    #[schemars(default = "FleetStrategySpec::default_type")]
    pub type_: String,

    /// How many servers may exist ABOVE the desired count during a rollout.
    /// An absolute number or a percentage string. Ignored for `Recreate`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_surge: Option<IntOrString>,

    /// How many servers may be missing BELOW the desired count during a
    /// rollout. Set this to 0 to guarantee capacity never dips. An absolute
    /// number or a percentage string. Ignored for `Recreate`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_unavailable: Option<IntOrString>,
}

impl FleetStrategySpec {
    fn default_type() -> String {
        "Recreate".to_string()
    }
}
