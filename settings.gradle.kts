rootProject.name = "shulker"

// UnifiedMetrics, built from source as a COMPOSITE BUILD rather than pulled
// from a repository.
//
// The agents embed it (see ShulkerServerAgentPaper), which needs our fork:
// upstream's Bukkit bootstrap is final and every collector binds to the
// concrete type. Getting that fork out as an artifact was the problem --
// JitPack builds it and finds nothing to publish, because the project signs
// unconditionally, pins a bungeecord-api snapshot that no longer resolves, and
// pulls Loom in for the Fabric platform, whose cache swamps the artifact scan.
// Patching all of that just to move a jar between two builds we both control is
// work with no product in it.
//
// includeBuild substitutes dev.cubxity.plugins:unifiedmetrics-* with the
// projects in third_party/unifiedmetrics automatically, on group and module --
// the version in the catalog is only there to satisfy the catalog format and is
// never resolved. The submodule pins the commit, so this is as reproducible as
// a version number and there is no publish step between a fix and a build that
// uses it.
//
// Requires a recursive clone. CI that checks out without submodules fails at
// configuration time with an unresolved dependency, not silently.
includeBuild("third_party/unifiedmetrics")

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
