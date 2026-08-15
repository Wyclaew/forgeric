package dev.forgeric.installer.target;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.forgeric.installer.core.InstallLog;
import dev.forgeric.installer.profile.VersionProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates or updates a Prism Launcher instance.
 *
 * <p>Prism resolves loader components from its own meta server, so the instance only has to
 * <em>declare</em> that it wants NeoForge — Prism downloads and patches it. That leaves
 * Forgeric with one job: drop the bridge jar into the instance's mods folder. The jar is
 * marked {@code FMLModType: LIBRARY}, which is what makes NeoForge load it on the plugin
 * layer and read its {@code META-INF/services} entries. No custom Prism component needed.
 */
public final class PrismTarget {
    private PrismTarget() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Prism's uid for the NeoForge component, as published by meta.prismlauncher.org. */
    private static final String NEOFORGE_UID = "net.neoforged";
    private static final String MINECRAFT_UID = "net.minecraft";

    /**
     * Creates a new instance directory under {@code <prismDataDir>/instances/<name>}.
     *
     * @return the created instance directory
     * @throws IOException if the instance already exists or cannot be written
     */
    public static Path createInstance(Path prismDataDir, String instanceName, VersionProfile profile,
                                      Path loaderJar, InstallLog log) throws IOException {
        Path instanceDir = prismDataDir.resolve("instances").resolve(instanceName);
        if (Files.exists(instanceDir)) {
            throw new IOException("An instance named '" + instanceName + "' already exists at " + instanceDir
                    + ". Choose another name, or install into the existing instance instead.");
        }

        Files.createDirectories(instanceDir);
        log.info("Created instance directory " + instanceDir);

        writeInstanceCfg(instanceDir, instanceName, profile);
        writeMmcPack(instanceDir, profile);

        Path minecraftDir = instanceDir.resolve(".minecraft");
        Files.createDirectories(minecraftDir.resolve("mods"));

        installLoaderJar(minecraftDir, loaderJar, log);
        log.info("Instance '" + instanceName + "' is ready. Prism will download NeoForge "
                + profile.neoForgeVersion() + " on first launch.");
        return instanceDir;
    }

    /**
     * Installs the bridge into an instance that already exists.
     *
     * <p>Deliberately does not touch mmc-pack.json: the user's instance may be on a different
     * NeoForge build than the profile names, and rewriting their component list to match would
     * be a surprising, hard-to-undo change. The version mismatch is reported instead.
     */
    public static void installIntoInstance(Path instanceDir, VersionProfile profile, Path loaderJar,
                                           InstallLog log) throws IOException {
        Path minecraftDir = resolveMinecraftDir(instanceDir);
        if (minecraftDir == null) {
            throw new IOException(instanceDir + " does not look like a Prism instance "
                    + "(no .minecraft or minecraft folder inside).");
        }

        checkInstanceLoader(instanceDir, profile, log);
        Files.createDirectories(minecraftDir.resolve("mods"));
        installLoaderJar(minecraftDir, loaderJar, log);
    }

    /** Prism uses {@code .minecraft} normally and {@code minecraft} on some older/portable setups. */
    public static Path resolveMinecraftDir(Path instanceDir) {
        Path dotMinecraft = instanceDir.resolve(".minecraft");
        if (Files.isDirectory(dotMinecraft)) return dotMinecraft;
        Path minecraft = instanceDir.resolve("minecraft");
        if (Files.isDirectory(minecraft)) return minecraft;
        return null;
    }

    /** Warns when the target instance is not running the NeoForge version this build was made for. */
    private static void checkInstanceLoader(Path instanceDir, VersionProfile profile, InstallLog log) {
        Path pack = instanceDir.resolve("mmc-pack.json");
        if (!Files.isRegularFile(pack)) {
            log.warn("No mmc-pack.json found — cannot verify this instance uses NeoForge.");
            return;
        }

        try (var reader = Files.newBufferedReader(pack, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            JsonArray components = root.getAsJsonArray("components");
            String neoForge = null;
            String minecraft = null;

            for (var element : components) {
                JsonObject component = element.getAsJsonObject();
                String uid = component.has("uid") ? component.get("uid").getAsString() : "";
                String version = component.has("version") ? component.get("version").getAsString() : "";
                if (NEOFORGE_UID.equals(uid)) neoForge = version;
                if (MINECRAFT_UID.equals(uid)) minecraft = version;
            }

            if (neoForge == null) {
                log.warn("This instance has no NeoForge component. Forgeric runs on top of NeoForge — "
                        + "add NeoForge to the instance before launching, or it will do nothing.");
            } else if (!neoForge.equals(profile.neoForgeVersion())) {
                log.warn("Instance uses NeoForge " + neoForge + ", this build targets "
                        + profile.neoForgeVersion() + ". It may still work, but is untested.");
            }

            if (minecraft != null && !minecraft.equals(profile.minecraft())) {
                log.warn("Instance is Minecraft " + minecraft + ", this build targets "
                        + profile.minecraft() + ".");
            }
        } catch (Exception e) {
            log.warn("Could not read mmc-pack.json: " + e.getMessage());
        }
    }

    /** Copies in the bridge jar, replacing any older Forgeric jar left from a previous install. */
    private static void installLoaderJar(Path minecraftDir, Path loaderJar, InstallLog log) throws IOException {
        Path modsDir = minecraftDir.resolve("mods");
        Files.createDirectories(modsDir);

        List<Path> stale = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("forgeric-loader") && name.endsWith(".jar");
            }).forEach(stale::add);
        }
        for (Path old : stale) {
            Files.delete(old);
            log.info("Removed previous " + old.getFileName());
        }

        Path destination = modsDir.resolve(loaderJar.getFileName().toString());
        Files.copy(loaderJar, destination, StandardCopyOption.REPLACE_EXISTING);
        log.info("Installed " + destination.getFileName() + " into " + modsDir);
    }

    private static void writeInstanceCfg(Path instanceDir, String instanceName, VersionProfile profile)
            throws IOException {
        String cfg = String.join("\n",
                "InstanceType=OneSix",
                "name=" + instanceName,
                "iconKey=default",
                "notes=Created by the Forgeric installer. Runs Forge (NeoForge " + profile.neoForgeVersion()
                        + ") and Fabric mods side by side on Minecraft " + profile.minecraft() + ".",
                "OverrideJavaArgs=false",
                "");
        Files.writeString(instanceDir.resolve("instance.cfg"), cfg, StandardCharsets.UTF_8);
    }

    private static void writeMmcPack(Path instanceDir, VersionProfile profile) throws IOException {
        JsonObject minecraft = new JsonObject();
        minecraft.addProperty("important", true);
        minecraft.addProperty("uid", MINECRAFT_UID);
        minecraft.addProperty("version", profile.minecraft());

        JsonObject neoforge = new JsonObject();
        neoforge.addProperty("uid", NEOFORGE_UID);
        neoforge.addProperty("version", profile.neoForgeVersion());

        JsonArray components = new JsonArray();
        components.add(minecraft);
        components.add(neoforge);

        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 1);
        root.add("components", components);

        Files.writeString(instanceDir.resolve("mmc-pack.json"), GSON.toJson(root), StandardCharsets.UTF_8);
    }
}
