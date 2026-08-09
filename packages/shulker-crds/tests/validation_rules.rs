//! Asserts the CEL validation rules survive in the generated CRDs.
//!
//! These rules are the difference between `kubectl apply` rejecting a bad spec
//! outright and the operator accepting it, reporting success, and then failing
//! every reconcile with an error only visible in its logs. A refactor that drops
//! a `#[x_kube(validation = ...)]` would otherwise be invisible.

use kube::CustomResourceExt;
use serde_json::Value;
use shulker_crds::v1alpha1::{
    minecraft_cluster::MinecraftCluster, minecraft_server::MinecraftServer,
    minecraft_server_fleet::MinecraftServerFleet, proxy_fleet::ProxyFleet,
};

/// Collects every `rule` string under any `x-kubernetes-validations` in the CRD.
fn rules_of(crd: &Value) -> Vec<String> {
    fn walk(value: &Value, found: &mut Vec<String>) {
        match value {
            Value::Object(map) => {
                if let Some(Value::Array(validations)) = map.get("x-kubernetes-validations") {
                    for validation in validations {
                        if let Some(rule) = validation.get("rule").and_then(Value::as_str) {
                            found.push(rule.to_string());
                        }
                    }
                }

                for nested in map.values() {
                    walk(nested, found);
                }
            }
            Value::Array(items) => {
                for item in items {
                    walk(item, found);
                }
            }
            _ => {}
        }
    }

    let mut found = Vec::new();
    walk(crd, &mut found);
    found
}

const MINESTOM_RULE: &str = "self.channel != 'Minestom' || has(self.customJar)";
const RESOURCE_REF_RULE: &str = "has(self.url) != has(self.urlFrom)";
const REDIS_RULE: &str = "self.type != 'Provided' || has(self.provided)";

#[test]
fn minecraft_server_requires_a_custom_jar_for_minestom() {
    let crd = serde_json::to_value(MinecraftServer::crd()).unwrap();
    assert!(
        rules_of(&crd).contains(&MINESTOM_RULE.to_string()),
        "MinecraftServer lost its Minestom rule"
    );
}

#[test]
fn minecraft_server_fleet_inherits_the_server_rules() {
    // The fleet embeds MinecraftServerSpec in its template, so the rule has to
    // appear there too or fleets bypass validation the standalone kind enforces.
    let crd = serde_json::to_value(MinecraftServerFleet::crd()).unwrap();
    let rules = rules_of(&crd);

    assert!(
        rules.contains(&MINESTOM_RULE.to_string()),
        "MinecraftServerFleet lost the Minestom rule inherited from its template"
    );
    assert!(rules.contains(&RESOURCE_REF_RULE.to_string()));
}

#[test]
fn resource_refs_must_declare_exactly_one_source() {
    for crd in [
        serde_json::to_value(MinecraftServer::crd()).unwrap(),
        serde_json::to_value(MinecraftServerFleet::crd()).unwrap(),
        serde_json::to_value(ProxyFleet::crd()).unwrap(),
    ] {
        assert!(
            rules_of(&crd).contains(&RESOURCE_REF_RULE.to_string()),
            "a CRD embedding ResourceRefSpec lost the one-source rule"
        );
    }
}

#[test]
fn provided_redis_requires_connection_details() {
    let crd = serde_json::to_value(MinecraftCluster::crd()).unwrap();
    assert!(
        rules_of(&crd).contains(&REDIS_RULE.to_string()),
        "MinecraftCluster lost the provided-Redis rule that RedisRef::from_cluster relies on"
    );
}

#[test]
fn resource_ref_rule_is_applied_at_every_embedding_site() {
    // plugins, patches, world and version.customJar all embed ResourceRefSpec.
    let crd = serde_json::to_value(MinecraftServer::crd()).unwrap();
    let occurrences = rules_of(&crd)
        .iter()
        .filter(|rule| *rule == RESOURCE_REF_RULE)
        .count();

    assert!(
        occurrences >= 4,
        "expected the ResourceRef rule at every embedding site, found {occurrences}"
    );
}

#[test]
fn sha256_is_constrained_to_a_hex_digest() {
    let crd = serde_json::to_string(&MinecraftServer::crd()).unwrap();
    assert!(
        crd.contains(r"^[a-f0-9]{64}$"),
        "the sha256 pattern is missing, so a malformed digest would only fail inside the init container"
    );
}
