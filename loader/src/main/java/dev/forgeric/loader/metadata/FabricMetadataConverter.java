package dev.forgeric.loader.metadata;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import dev.forgeric.loader.ForgericConstants;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a parsed {@code fabric.mod.json} into the config shape NeoForge's own
 * {@code ModFileInfo} / {@code ModInfo} expect from a {@code neoforge.mods.toml}.
 *
 * <p>This is the core trick of the bridge. Rather than implementing {@code IModInfo},
 * {@code IModFileInfo}, dependency records and version ranges by hand — several hundred
 * lines that would need re-checking against every NeoForge release — Forgeric synthesizes
 * the in-memory config NeoForge already knows how to consume, and lets NeoForge build its
 * own metadata objects from it. Fewer moving parts, and it inherits NeoForge's validation.
 *
 * <p>The produced structure mirrors a mods.toml:
 * <pre>
 * modLoader = "forgeric-fabric"
 * loaderVersion = "[1,)"
 * license = "..."
 * [[mods]]
 *   modId = "..."          # sanitized, see {@link ForgericIds}
 *   version = "..."
 * [[dependencies.&lt;modId&gt;]]
 *   modId = "..."
 *   versionRange = "[1,)"  # translated by {@link FabricVersionRanges}
 * [[mixins]]
 *   config = "..."         # NeoForge loads these itself; no separate mixin bridge needed
 * </pre>
 */
public final class FabricMetadataConverter {
    private FabricMetadataConverter() {}

    /** NeoForge rejects a mod file with a blank license, but Fabric treats it as optional. */
    private static final String UNKNOWN_LICENSE = "Unknown (declared by a Fabric mod)";

    /**
     * Dependencies on these ids are dropped rather than translated: they describe the loader
     * environment itself, which on the NeoForge side is satisfied by different mods with
     * different version schemes. Keeping them would make every Fabric mod fail its dependency
     * check ("fabricloader 0.19.3 not found").
     */
    private static final List<String> ENVIRONMENT_MOD_IDS = List.of(
            ForgericConstants.FABRIC_LOADER_MOD_ID,
            ForgericConstants.MINECRAFT_MOD_ID,
            "java",
            "neoforge",
            "forge");

    public static CommentedConfig toNeoForgeConfig(FabricModJson mod) {
        CommentedConfig root = CommentedConfig.inMemory();
        String modId = ForgericIds.toNeoForge(mod.id());

        root.set("modLoader", ForgericConstants.LANGUAGE_LOADER);
        root.set("loaderVersion", "[1,)");
        root.set("license", mod.license() == null || mod.license().isBlank() ? UNKNOWN_LICENSE : mod.license());

        CommentedConfig modEntry = CommentedConfig.inMemory();
        modEntry.set("modId", modId);
        modEntry.set("version", normalizeVersion(mod.version()));
        modEntry.set("displayName", mod.name());
        if (!mod.description().isBlank()) {
            modEntry.set("description", mod.description());
        }
        root.set("mods", List.of(modEntry));

        List<Config> dependencies = convertDependencies(mod);
        if (!dependencies.isEmpty()) {
            // Set via an explicit path list: a mod id may contain dots, which NightConfig would
            // otherwise read as path separators.
            root.set(List.of("dependencies", modId), dependencies);
        }

        List<Config> mixins = convertMixins(mod);
        if (!mixins.isEmpty()) {
            root.set("mixins", mixins);
        }

        return root;
    }

    private static List<Config> convertDependencies(FabricModJson mod) {
        List<Config> result = new ArrayList<>();
        for (FabricModJson.Dependency dependency : mod.dependencies()) {
            if (ENVIRONMENT_MOD_IDS.contains(dependency.modId())) {
                continue;
            }
            Config entry = CommentedConfig.inMemory();
            entry.set("modId", ForgericIds.toNeoForge(dependency.modId()));
            entry.set("type", switch (dependency.kind()) {
                case REQUIRED -> "required";
                case OPTIONAL -> "optional";
                case INCOMPATIBLE -> "incompatible";
                case DISCOURAGED -> "discouraged";
            });
            entry.set("versionRange", FabricVersionRanges.toMavenRange(dependency.versionRanges()));
            entry.set("ordering", "NONE");
            entry.set("side", "BOTH");
            result.add(entry);
        }
        return result;
    }

    /**
     * Hands Fabric's mixin configs to NeoForge's own mixin loading.
     *
     * <p>This works only because both loaders ship the identical Mixin build
     * ({@code net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7}) — NeoForge already runs Fabric's
     * fork. Per-environment filtering is intentionally not applied here: a Fabric mixin config
     * carries its own {@code client}/{@code server} sections, which Mixin resolves against the
     * running side on its own.
     */
    private static List<Config> convertMixins(FabricModJson mod) {
        List<Config> result = new ArrayList<>();
        for (FabricModJson.MixinConfig mixin : mod.mixins()) {
            Config entry = CommentedConfig.inMemory();
            entry.set("config", mixin.config());
            result.add(entry);
        }
        return result;
    }

    /**
     * NeoForge requires versions to match {@code ^\d+.*}. Fabric permits leading letters
     * ({@code v1.2}) and unexpanded placeholders, so anything non-conforming is prefixed
     * rather than rejected.
     */
    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0.0.0";
        }
        char first = version.charAt(0);
        if (first >= '0' && first <= '9') {
            return version;
        }
        // Strip a conventional "v" prefix, otherwise fall back to a valid placeholder.
        if ((first == 'v' || first == 'V') && version.length() > 1
                && Character.isDigit(version.charAt(1))) {
            return version.substring(1);
        }
        return "0.0.0-" + version;
    }
}
