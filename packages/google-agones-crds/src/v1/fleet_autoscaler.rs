use k8s_openapi::{
    api::admissionregistration::v1::WebhookClientConfig,
    apimachinery::pkg::util::intstr::IntOrString,
};
use kube::CustomResource;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use strum::{Display, IntoStaticStr};

#[derive(CustomResource, Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[kube(
    kind = "FleetAutoscaler",
    group = "autoscaling.agones.dev",
    version = "v1",
    namespaced,
    status = "FleetAutoscalerStatus"
)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalerSpec {
    pub fleet_name: String,
    pub policy: FleetAutoscalerPolicySpec,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalerPolicySpec {
    #[serde(default)]
    pub type_: FleetAutoscalerPolicy,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub buffer: Option<FleetAutoscalerPolicyBufferSpec>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub webhook: Option<WebhookClientConfig>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub counter: Option<FleetAutoscalerPolicyCounterSpec>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default, IntoStaticStr, Display)]
pub enum FleetAutoscalerPolicy {
    #[default]
    Buffer,
    Webhook,
    /// Scales on aggregate spare capacity of a named counter across the fleet.
    ///
    /// The difference from `Buffer` is granularity. `Buffer` counts whole
    /// servers, and a server is either Ready or Allocated -- so one player and
    /// forty players on a 40-slot server look identical to it. `Counter`
    /// measures actual free slots, so a fleet of half-full servers scales out
    /// before every one of them is saturated.
    ///
    /// Requires Agones' `CountsAndLists` feature gate, and requires the counter
    /// to be declared in the `GameServer` spec and kept up to date through the
    /// SDK.
    Counter,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalerPolicyCounterSpec {
    /// Name of the counter to scale on, as declared in the `GameServer` spec.
    pub key: String,
    /// Spare capacity to maintain across the fleet. An absolute number of free
    /// slots, or a percentage of total capacity.
    pub buffer_size: IntOrString,
    pub min_capacity: i64,
    pub max_capacity: i64,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalerPolicyBufferSpec {
    pub max_replicas: i32,
    pub min_replicas: i32,
    pub buffer_size: IntOrString,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct FleetAutoscalerStatus {
    pub current_replicas: i32,
    pub desired_replicas: i32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_scale_time: Option<k8s_openapi::apimachinery::pkg::apis::meta::v1::Time>,
    pub able_to_scale: bool,
    pub scaling_limited: bool,
}
