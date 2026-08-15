// The loader runs inside NeoForge's classpath at game runtime, so everything it talks to is
// already present there. Dependencies are compileOnly on purpose: shipping our own copy of
// Mixin, ASM or Fabric Loader would put duplicate classes on the classpath.
dependencies {
    compileOnly(libs.fml.loader)
    compileOnly(libs.fabric.loader)
    compileOnly(libs.mixin)
    compileOnly(libs.asm)
    compileOnly(libs.asm.tree)
    compileOnly(libs.nightconfig.core)
    compileOnly(libs.nightconfig.toml)
    compileOnly(libs.maven.artifact)
    compileOnly(libs.gson)
    compileOnly(libs.slf4j)
    compileOnly(libs.log4j.api)
    compileOnly(libs.annotations)

    // The metadata translation is pure logic and is the part most likely to break silently
    // when a Fabric mod uses a form we did not anticipate, so it carries tests.
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.gson)
    testImplementation(libs.maven.artifact)
    testImplementation(libs.nightconfig.core)
    testImplementation(libs.nightconfig.toml)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Fabric's initializer interfaces are bundled into our jar.
//
// Dropping the whole fabric-loader jar into the mods folder instead would be actively harmful:
// it registers an org.spongepowered.asm.service.IGlobalPropertyService via META-INF/services,
// and so does NeoForge. Two providers for one Mixin service is an unpredictable coin flip.
// Only net/fabricmc/api/** is taken (the interfaces mods implement), never the service files.
val fabricApiClasses: Configuration = configurations.resolvable("fabricApiClasses").get()

fabricApiClasses.isTransitive = false

dependencies {
    fabricApiClasses(libs.fabric.loader)
}

val extractFabricApi = tasks.register<Copy>("extractFabricApi") {
    from(provider { zipTree(fabricApiClasses.singleFile) })
    include("net/fabricmc/api/**")
    into(layout.buildDirectory.dir("fabric-api-classes"))
}

tasks.jar {
    // The installer removes previous installs by matching this prefix, and the file sits in a
    // mods folder next to the user's own mods, so it needs an unambiguous name.
    archiveBaseName.set("forgeric-loader")

    dependsOn(extractFabricApi)
    from(layout.buildDirectory.dir("fabric-api-classes"))

    manifest {
        attributes(
            "Specification-Title" to "Forgeric Loader",
            "Specification-Vendor" to "Forgeric",
            "Implementation-Title" to "forgeric-loader",
            "Implementation-Version" to project.version,
            // NeoForge only reads service providers from jars marked as libraries.
            "FMLModType" to "LIBRARY",
        )
    }
}
