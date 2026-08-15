package dev.forgeric.loader.discovery;

import dev.forgeric.loader.ForgericConstants;
import dev.forgeric.loader.metadata.FabricMetadataConverter;
import dev.forgeric.loader.metadata.FabricModJson;
import dev.forgeric.loader.metadata.ForgericIds;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.fml.loading.moddiscovery.NightConfigWrapper;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.InvalidModFileException;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Teaches NeoForge to recognize Fabric mod jars.
 *
 * <p>Registered through {@code META-INF/services}, NeoForge calls this for every jar it finds
 * in the mods folder. Returning {@code null} means "not mine" and lets NeoForge's own readers
 * proceed — so plain Forge mods are never touched by Forgeric.
 */
public class FabricModFileReader implements IModFileReader {
    private static final Logger LOGGER = LoggerFactory.getLogger("Forgeric/Discovery");

    @Override
    @Nullable
    public IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        if (!jar.containsFile(ForgericConstants.FABRIC_MOD_JSON)) {
            return null; // not a Fabric mod
        }

        // A jar carrying both metadata files is a multi-loader build (Architectury and friends).
        // Its NeoForge half is the better path — it is native, and claiming it here would run
        // the mod through the bridge for no reason.
        if (jar.containsFile(ForgericConstants.NEOFORGE_MODS_TOML)) {
            LOGGER.debug("Skipping {}: multi-loader jar, letting NeoForge take it", jar.getPrimaryPath());
            return null;
        }

        ModJarMetadata jarMetadata = new ModJarMetadata();
        ModFile modFile = new ModFile(jar, jarMetadata, FabricModFileReader::parseMetadata, attributes.withReader(this));
        jarMetadata.setModFile(modFile);
        return modFile;
    }

    /**
     * Reads {@code fabric.mod.json} and hands NeoForge the equivalent of a parsed mods.toml.
     * Runs lazily, when NeoForge first asks the mod file for its info.
     */
    private static IModFileInfo parseMetadata(IModFile file) {
        FabricModJson mod;
        try (InputStream in = file.getContents().openFile(ForgericConstants.FABRIC_MOD_JSON)) {
            if (in == null) {
                throw new InvalidModFileException("fabric.mod.json disappeared while reading", null);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                mod = FabricModJson.read(reader);
            }
        } catch (IOException e) {
            throw new InvalidModFileException("Could not read fabric.mod.json: " + e.getMessage(), null);
        }

        FabricModRegistry.register(mod);

        String neoForgeId = ForgericIds.toNeoForge(mod.id());
        if (!neoForgeId.equals(mod.id())) {
            LOGGER.debug("Fabric mod '{}' is exposed to NeoForge as '{}'", mod.id(), neoForgeId);
        }
        LOGGER.info("Discovered Fabric mod {} ({}) v{}", mod.id(), mod.name(), mod.version());
        warnAboutFabricApi(mod);

        NightConfigWrapper config = new NightConfigWrapper(FabricMetadataConverter.toNeoForgeConfig(mod));
        return new ModFileInfo((ModFile) file, config, config::setFile);
    }

    /**
     * Explains, in the log, why a Fabric API dependency is about to fail the mod.
     *
     * <p>The dependency is deliberately left in the converted metadata so NeoForge rejects the
     * mod through its normal dependency check rather than letting it load and die on a
     * NoClassDefFoundError mid-launch. NeoForge's own message names a mod called "fabric_api"
     * that the player has no way to install, so the real explanation is logged here.
     */
    private static void warnAboutFabricApi(FabricModJson mod) {
        boolean needsApi = mod.dependencies().stream()
                .filter(d -> d.kind() == FabricModJson.Dependency.Kind.REQUIRED)
                .anyMatch(d -> d.modId().equals("fabric-api")
                        || d.modId().equals("fabric")
                        || d.modId().startsWith("fabric-"));
        if (needsApi) {
            LOGGER.warn("Mod '{}' requires the Fabric API, which Forgeric does not bridge yet, so "
                    + "NeoForge will reject it as a missing 'fabric_api' dependency. Use this mod's "
                    + "NeoForge build if one exists.", mod.id());
        }
    }

    @Override
    public String toString() {
        return "forgeric fabric.mod.json reader";
    }
}
