use http::{Request, Response};
use kube::client::Body;
use kube::{Client, core::ObjectMeta};
use shulker_crds::v1alpha1::minecraft_cluster::{
    MinecraftCluster, MinecraftClusterExternalServerSpec, MinecraftClusterSpec,
};
use std::sync::LazyLock;

pub static TEST_CLUSTER: LazyLock<MinecraftCluster> = LazyLock::new(|| MinecraftCluster {
    metadata: ObjectMeta {
        namespace: Some("default".to_string()),
        name: Some("my-cluster".to_string()),
        ..ObjectMeta::default()
    },
    spec: MinecraftClusterSpec {
        network_admins: None,
        redis: None,
        external_servers: Some(vec![MinecraftClusterExternalServerSpec {
            name: "my-external-server".to_string(),
            address: "127.0.0.1:25565".to_string(),
            tags: vec!["game".to_string()],
        }]),
    },
    status: None,
});

pub fn create_client_mock() -> Client {
    let (mock_service, _) = tower_test::mock::pair::<Request<Body>, Response<Body>>();
    Client::new(mock_service, "default")
}
