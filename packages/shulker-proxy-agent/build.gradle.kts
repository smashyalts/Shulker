plugins {
    alias(libs.plugins.buildconfig)
}

dependencies {
    // Shulker
    commonImplementation(project(":packages:shulker-proxy-api"))
    commonImplementation(project(":packages:shulker-cluster-api-impl"))

    // Utils
    commonImplementation(libs.apache.commons.io)
    commonImplementation(libs.snakeyaml)
    commonImplementation(libs.guava)

    // UnifiedMetrics, embedded rather than shipped as a second plugin. Only
    // `implementation` here, unlike the Paper agent: Velocity composes the
    // bootstrap rather than extending it, so the type never leaves this module.
    velocityImplementation(libs.unifiedmetrics.velocity)

    // The API in `common` so PlayerAnalyticsService can register its own
    // collection. Deliberately the API and not a platform: common compiles once
    // for BOTH Velocity and BungeeCord, and a platform dependency here would
    // put Velocity's bootstrap on BungeeCord's classpath.
    //
    // BungeeCord has no UnifiedMetrics at runtime, so the registration is
    // wrapped in catch(Throwable) there and the service degrades to Redis-only.
    commonImplementation(libs.unifiedmetrics.api)
}

tasks.named("processBungeecordResources", ProcessResources::class.java) {
    inputs.property("version", project.version)
    expand("version" to project.version)
}

ktlint {
    filter {
        exclude {
            it.file.path.contains(layout.buildDirectory.dir("generated").get().toString())
        }
    }
}

buildConfig {
    packageName("io.shulkermc.proxy")

    sourceSets.getByName("velocity") {
        buildConfigField("String", "VERSION", "\"${project.version}\"")
    }
}
