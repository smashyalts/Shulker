use serde::{de::DeserializeOwned, Serialize};
use serde_json::{Map, Value};

/// Key Kubernetes uses to identify elements of most `PodSpec` lists.
const MERGE_KEY: &str = "name";

/// Merges `overlay` onto `base` the way Kubernetes' strategic merge patch does
/// for pod templates.
///
/// Plain JSON merge (RFC 7386) is not usable here: it replaces arrays wholesale,
/// so an overlay that adds one volume mount would drop every mount the operator
/// generated. Kubernetes instead merges the `PodSpec` lists by the `name` of
/// each element, which is what this reproduces:
///
/// - Objects merge key by key, recursively.
/// - Arrays whose elements all carry a `name` merge by that name; an element
///   whose name is absent from the base is appended, one that matches is merged
///   into the existing element.
/// - Every other array, and every scalar, is replaced by the overlay.
/// - An explicit `null` in the overlay removes the key, matching RFC 7386.
pub fn strategic_merge(base: Value, overlay: Value) -> Value {
    match (base, overlay) {
        (Value::Object(base), Value::Object(overlay)) => {
            Value::Object(merge_objects(base, overlay))
        }
        (Value::Array(base), Value::Array(overlay)) if is_keyed(&base) && is_keyed(&overlay) => {
            Value::Array(merge_keyed_arrays(base, overlay))
        }
        (_, overlay) => overlay,
    }
}

fn merge_objects(mut base: Map<String, Value>, overlay: Map<String, Value>) -> Map<String, Value> {
    for (key, overlay_value) in overlay {
        if overlay_value.is_null() {
            base.remove(&key);
            continue;
        }

        let merged = match base.remove(&key) {
            Some(base_value) => strategic_merge(base_value, overlay_value),
            None => overlay_value,
        };

        base.insert(key, merged);
    }

    base
}

/// A list is merged by name only when every element is an object carrying a
/// string `name`. Lists like `tolerations` have no such key and are replaced.
fn is_keyed(values: &[Value]) -> bool {
    !values.is_empty()
        && values
            .iter()
            .all(|value| value.get(MERGE_KEY).and_then(Value::as_str).is_some())
}

fn merge_keyed_arrays(base: Vec<Value>, overlay: Vec<Value>) -> Vec<Value> {
    let mut merged = base;

    for overlay_value in overlay {
        let name = overlay_value
            .get(MERGE_KEY)
            .and_then(Value::as_str)
            .expect("is_keyed guarantees a string name")
            .to_string();

        match merged
            .iter_mut()
            .find(|value| value.get(MERGE_KEY).and_then(Value::as_str) == Some(name.as_str()))
        {
            Some(existing) => {
                let taken = std::mem::replace(existing, Value::Null);
                *existing = strategic_merge(taken, overlay_value);
            }
            None => merged.push(overlay_value),
        }
    }

    merged
}

/// Applies a JSON overlay to a typed Kubernetes object.
pub fn apply_overlay<T>(base: &T, overlay: &Value) -> Result<T, serde_json::Error>
where
    T: Serialize + DeserializeOwned,
{
    let merged = strategic_merge(serde_json::to_value(base)?, overlay.clone());
    serde_json::from_value(merged)
}

#[cfg(test)]
mod tests {
    use k8s_openapi::api::core::v1::PodTemplateSpec;
    use serde_json::json;

    use super::{apply_overlay, strategic_merge};

    #[test]
    fn scalars_are_replaced() {
        let merged = strategic_merge(json!({"a": 1, "b": "x"}), json!({"b": "y"}));
        assert_eq!(merged, json!({"a": 1, "b": "y"}));
    }

    #[test]
    fn nested_objects_merge_rather_than_replace() {
        let merged = strategic_merge(
            json!({"spec": {"a": 1, "b": 2}}),
            json!({"spec": {"b": 3, "c": 4}}),
        );
        assert_eq!(merged, json!({"spec": {"a": 1, "b": 3, "c": 4}}));
    }

    #[test]
    fn explicit_null_removes_a_key() {
        let merged = strategic_merge(json!({"a": 1, "b": 2}), json!({"b": null}));
        assert_eq!(merged, json!({"a": 1}));
    }

    #[test]
    fn named_list_entries_merge_by_name() {
        let merged = strategic_merge(
            json!({"containers": [{"name": "app", "image": "base", "tty": true}]}),
            json!({"containers": [{"name": "app", "image": "override"}]}),
        );

        // The generated `tty` survives; only `image` is replaced.
        assert_eq!(
            merged,
            json!({"containers": [{"name": "app", "image": "override", "tty": true}]})
        );
    }

    #[test]
    fn unmatched_named_entries_are_appended() {
        let merged = strategic_merge(
            json!({"volumes": [{"name": "a"}]}),
            json!({"volumes": [{"name": "b"}]}),
        );
        assert_eq!(merged, json!({"volumes": [{"name": "a"}, {"name": "b"}]}));
    }

    #[test]
    fn unnamed_lists_are_replaced_wholesale() {
        // `tolerations` have no merge key, so Kubernetes replaces them.
        let merged = strategic_merge(
            json!({"tolerations": [{"key": "a"}, {"key": "b"}]}),
            json!({"tolerations": [{"key": "c"}]}),
        );
        assert_eq!(merged, json!({"tolerations": [{"key": "c"}]}));
    }

    #[test]
    fn scalar_lists_are_replaced_wholesale() {
        let merged = strategic_merge(json!({"command": ["a", "b"]}), json!({"command": ["c"]}));
        assert_eq!(merged, json!({"command": ["c"]}));
    }

    #[test]
    fn overlay_only_keys_are_added() {
        let merged = strategic_merge(json!({"a": 1}), json!({"b": {"c": 2}}));
        assert_eq!(merged, json!({"a": 1, "b": {"c": 2}}));
    }

    #[test]
    fn merging_is_deep_inside_list_entries() {
        let merged = strategic_merge(
            json!({"containers": [{
                "name": "app",
                "volumeMounts": [{"name": "config", "mountPath": "/config"}],
            }]}),
            json!({"containers": [{
                "name": "app",
                "volumeMounts": [{"name": "data", "mountPath": "/data"}],
            }]}),
        );

        let mounts = merged["containers"][0]["volumeMounts"].as_array().unwrap();
        assert_eq!(mounts.len(), 2, "generated mount was dropped: {merged}");
    }

    #[test]
    fn applies_to_a_real_pod_template() {
        let base: PodTemplateSpec = serde_json::from_value(json!({
            "spec": {
                "containers": [{
                    "name": "minecraft-server",
                    "image": "itzg/minecraft-server:pinned",
                    "env": [{"name": "TYPE", "value": "PAPER"}],
                }],
                "volumes": [{"name": "server-data", "emptyDir": {}}],
            }
        }))
        .unwrap();

        let overlay = json!({
            "spec": {
                "priorityClassName": "high",
                "containers": [{
                    "name": "minecraft-server",
                    "resources": {"limits": {"memory": "4Gi"}},
                    "env": [{"name": "EXTRA", "value": "1"}],
                }],
                "volumes": [{"name": "extra", "emptyDir": {}}],
            }
        });

        let merged: PodTemplateSpec = apply_overlay(&base, &overlay).unwrap();
        let spec = merged.spec.unwrap();

        // A field the CRD never modelled is now reachable.
        assert_eq!(spec.priority_class_name, Some("high".to_string()));

        let container = &spec.containers[0];
        assert_eq!(
            container.image,
            Some("itzg/minecraft-server:pinned".to_string()),
            "the generated image should survive an overlay that does not set it"
        );
        assert!(container.resources.is_some());

        let env = container.env.as_ref().unwrap();
        assert_eq!(env.len(), 2, "generated env was dropped");

        assert_eq!(spec.volumes.as_ref().unwrap().len(), 2);
    }

    #[test]
    fn overlay_can_replace_the_generated_image() {
        let base: PodTemplateSpec = serde_json::from_value(json!({
            "spec": {"containers": [{"name": "proxy", "image": "base"}]}
        }))
        .unwrap();

        let merged: PodTemplateSpec = apply_overlay(
            &base,
            &json!({"spec": {"containers": [{"name": "proxy", "image": "mine"}]}}),
        )
        .unwrap();

        assert_eq!(
            merged.spec.unwrap().containers[0].image,
            Some("mine".to_string())
        );
    }

    #[test]
    fn an_empty_overlay_is_the_identity() {
        let base: PodTemplateSpec = serde_json::from_value(json!({
            "spec": {"containers": [{"name": "proxy", "image": "base"}]}
        }))
        .unwrap();

        let merged: PodTemplateSpec = apply_overlay(&base, &json!({})).unwrap();
        assert_eq!(merged, base);
    }
}
