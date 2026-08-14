dependencies {
    api(project(":packages:shulker-cluster-api"))

    // Agones
    api(project(":packages:google-agones-sdk"))

    // Kubernetes
    compileOnlyApi(libs.kubernetes.client.api)
    runtimeOnly(libs.kubernetes.client)
    implementation(libs.kubernetes.client.http)

    // Cache & PubSub
    // `api`, not `implementation`: ShulkerClusterAPIImpl exposes `jedisPool` as
    // a public JedisPool, so Jedis is part of this module's ABI whether or not
    // it is declared as such. Under `implementation` a consumer could name the
    // field but not the type -- which is exactly what the proxy agent hit when
    // it started reading Redis directly for player analytics.
    api(libs.jedis)
}
