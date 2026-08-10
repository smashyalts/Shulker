use std::collections::BTreeMap;

use k8s_openapi::api::core::v1::PodTemplateSpec;
use kube::CustomResource;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

#[derive(CustomResource, Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[kube(
    kind = "GameServer",
    group = "agones.dev",
    version = "v1",
    namespaced,
    status = "GameServerStatus"
)]
#[serde(rename_all = "camelCase")]
pub struct GameServerSpec {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ports: Option<Vec<GameServerPortSpec>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub health: Option<GameServerHealthSpec>,
    pub template: PodTemplateSpec,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub eviction: Option<GameServerEvictionSpec>,

    /// Counters this `GameServer` starts with, keyed by name.
    ///
    /// Requires Agones' `CountsAndLists` feature gate. Declaring a counter here
    /// is what makes it addressable by the SDK at runtime and by a `Counter`
    /// fleet autoscaler policy; a counter that is only ever set from the SDK
    /// without being declared does not exist as far as the autoscaler is
    /// concerned.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub counters: Option<BTreeMap<String, GameServerCounterSpec>>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GameServerCounterSpec {
    /// Starting count.
    pub count: i64,
    /// Maximum the count may reach. For a player counter this is the server's
    /// player limit, and it is what a `Counter` autoscaler measures spare
    /// capacity against.
    pub capacity: i64,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct GameServerPortSpec {
    pub name: String,
    pub container_port: i32,
    pub protocol: String,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct GameServerHealthSpec {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub disabled: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub period_seconds: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub failure_threshold: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub initial_delay_seconds: Option<i32>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct GameServerEvictionSpec {
    pub safe: String,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
#[serde(rename_all = "camelCase")]
pub struct GameServerStatus {
    pub state: String,
    pub address: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub ports: Vec<GameServerStatusPort>,

    /// Live counter values, as reported through the SDK by whatever is running
    /// inside the `GameServer`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub counters: Option<BTreeMap<String, GameServerCounterStatus>>,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GameServerCounterStatus {
    pub count: i64,
    pub capacity: i64,
}

#[derive(Deserialize, Serialize, Clone, Debug, JsonSchema, Default)]
pub struct GameServerStatusPort {
    pub name: String,
    pub port: i32,
}
