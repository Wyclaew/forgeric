package dev.forgeric.installer.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One entry from {@code profiles/}: everything that varies between Minecraft versions.
 *
 * <p>The point of this indirection is that supporting a new Minecraft version should mean
 * adding a JSON file, not editing Java. Anything version-specific belongs here rather than
 * in installer logic.
 */
public final class VersionProfile {
    private final String minecraft;
    private final int javaMajor;
    private final boolean obfuscated;
    private final String neoForgeVersion;
    private final String neoForgeMaven;
    private final String fabricLoaderVersion;
    private final String fabricMaven;
    private final String forgericVersion;
    private final String status;
    private final boolean fabricApiSupported;

    private VersionProfile(String minecraft, int javaMajor, boolean obfuscated, String neoForgeVersion,
                           String neoForgeMaven, String fabricLoaderVersion, String fabricMaven,
                           String forgericVersion, String status, boolean fabricApiSupported) {
        this.minecraft = minecraft;
        this.javaMajor = javaMajor;
        this.obfuscated = obfuscated;
        this.neoForgeVersion = neoForgeVersion;
        this.neoForgeMaven = neoForgeMaven;
        this.fabricLoaderVersion = fabricLoaderVersion;
        this.fabricMaven = fabricMaven;
        this.forgericVersion = forgericVersion;
        this.status = status;
        this.fabricApiSupported = fabricApiSupported;
    }

    public String minecraft() { return minecraft; }
    public int javaMajor() { return javaMajor; }
    public String neoForgeVersion() { return neoForgeVersion; }
    public String neoForgeMaven() { return neoForgeMaven; }
    public String fabricLoaderVersion() { return fabricLoaderVersion; }
    public String fabricMaven() { return fabricMaven; }
    public String forgericVersion() { return forgericVersion; }
    public String status() { return status; }
    public boolean fabricApiSupported() { return fabricApiSupported; }

    /**
     * Whether this Minecraft version still ships obfuscated.
     * When true, Forgeric cannot bridge the two loaders without a runtime remapper,
     * which does not exist yet — the installer refuses rather than producing a broken install.
     */
    public boolean isObfuscated() { return obfuscated; }

    public static VersionProfile parse(JsonObject root) {
        JsonObject neoforge = root.getAsJsonObject("neoforge");
        JsonObject fabric = root.getAsJsonObject("fabric");
        JsonObject forgeric = root.has("forgeric") ? root.getAsJsonObject("forgeric") : new JsonObject();
        JsonObject supported = root.has("supported") ? root.getAsJsonObject("supported") : new JsonObject();

        return new VersionProfile(
                root.get("minecraft").getAsString(),
                root.has("javaMajor") ? root.get("javaMajor").getAsInt() : 21,
                root.has("obfuscated") && root.get("obfuscated").getAsBoolean(),
                neoforge.get("version").getAsString(),
                neoforge.has("maven") ? neoforge.get("maven").getAsString() : "https://maven.neoforged.net/releases",
                fabric.get("loader").getAsString(),
                fabric.has("maven") ? fabric.get("maven").getAsString() : "https://maven.fabricmc.net/",
                forgeric.has("version") ? forgeric.get("version").getAsString() : "0.1.0",
                supported.has("status") ? supported.get("status").getAsString() : "unknown",
                supported.has("fabricApi") && supported.get("fabricApi").getAsBoolean());
    }

    /** Loads a profile bundled inside the installer jar. */
    public static VersionProfile loadBundled(String minecraftVersion) throws IOException {
        String resource = "/profiles/" + minecraftVersion + ".json";
        try (InputStream in = VersionProfile.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("No bundled profile for Minecraft " + minecraftVersion
                        + ". Available: " + String.join(", ", listBundled()));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return parse(JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }

    public static VersionProfile loadFile(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        }
    }

    /**
     * {@return Minecraft versions this installer ships profiles for}
     *
     * <p>Read from an index resource rather than by scanning the jar: enumerating a jar's own
     * contents at runtime is awkward when the installer is launched in unusual ways.
     */
    public static List<String> listBundled() {
        List<String> versions = new ArrayList<>();
        try (InputStream in = VersionProfile.class.getResourceAsStream("/profiles/index.txt")) {
            if (in == null) return List.of();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        versions.add(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(versions);
    }
}
