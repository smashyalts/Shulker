rootProject.name = "shulker"


// The version catalog lives in gradle/libs.versions.toml, which Gradle loads
// automatically. It used to be declared inline here.

fun includeBindingProject(name: String) {
    include(":packages:$name")
    project(":packages:$name").projectDir = file("packages/$name/bindings/java")
}

includeBindingProject("google-agones-sdk")
includeBindingProject("google-open-match-sdk")
includeBindingProject("shulker-sdk")

include(":packages:shulker-cluster-api")
include(":packages:shulker-cluster-api-impl")

include(":packages:shulker-proxy-api")
include(":packages:shulker-proxy-agent")

include(":packages:shulker-server-api")
include(":packages:shulker-server-agent")
include(":packages:shulker-server-minestom-demo")
