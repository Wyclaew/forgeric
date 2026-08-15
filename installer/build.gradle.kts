// The installer must run by double-click on a machine that has nothing but a JRE,
// so everything it needs is bundled into one jar.
dependencies {
    implementation(libs.gson)
    // Mixin conflicts are the hardest kind to diagnose after the fact, and the only place
    // the real target classes are recorded is the mixin class bytecode, so the scanner reads it.
    implementation(libs.asm)
    implementation(libs.asm.tree)
    // neoforge.mods.toml, to identify mods on the NeoForge side of the folder.
    implementation(libs.nightconfig.core)
    implementation(libs.nightconfig.toml)

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.jar {
    archiveBaseName.set("forgeric-installer")

    manifest {
        attributes(
            "Main-Class" to "dev.forgeric.installer.ForgericInstaller",
            "Implementation-Title" to "Forgeric Installer",
            "Implementation-Version" to project.version,
            "Enable-Native-Access" to "ALL-UNNAMED",
        )
    }

    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Signature files from dependencies invalidate the merged jar.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }

    // Ship the version profiles inside the installer so it works offline-ish and
    // always agrees with the loader it was built alongside.
    from(rootProject.file("profiles")) {
        into("profiles")
        include("*.json")
        exclude("schema.json") // authoring aid, not needed at install time
    }

    // Embed the loader so distribution is a single file, the way Forge and Fabric do it.
    from(project(":loader").tasks.named("jar")) {
        into("payload")
    }
}

// The installer resolves available versions from this index rather than scanning its own jar.
val profilesIndex = tasks.register("profilesIndex") {
    val profilesDir = rootProject.file("profiles")
    val outputFile = layout.buildDirectory.file("generated/profiles/index.txt")
    inputs.dir(profilesDir)
    outputs.file(outputFile)
    doLast {
        val versions = profilesDir.listFiles()
            ?.filter { it.name.endsWith(".json") && it.name != "schema.json" }
            ?.map { it.name.removeSuffix(".json") }
            ?.sorted()
            .orEmpty()
        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(versions.joinToString("\n", postfix = "\n"))
    }
}

tasks.jar {
    from(profilesIndex) {
        into("profiles")
    }
}
