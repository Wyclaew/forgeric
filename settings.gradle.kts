// Lets Gradle fetch a JDK 25 toolchain on machines that don't have one installed,
// so the build works without asking contributors to install Java by hand first.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "forgeric"

include("loader")
include("installer")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        // FancyModLoader pulls com.mojang:logging, which only lives here.
        maven("https://libraries.minecraft.net/") { name = "Minecraft" }
    }
}
