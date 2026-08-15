package dev.forgeric.loader.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for {@code fabric.mod.json}.
 *
 * <p>This deliberately does not reuse Fabric Loader's own parser: that parser lives in
 * {@code net.fabricmc.loader.impl.metadata} and produces {@code LoaderModMetadata}, which is
 * bound to Fabric's own mod container model. Forgeric needs the raw declarations so it can
 * translate them into NeoForge's metadata model instead. The schema is stable and small
 * enough that reading it directly is less fragile than adapting Fabric's internals.
 *
 * <p>Fields Forgeric does not act on yet (authors, contact, icon) are skipped rather than
 * stored, to keep it obvious what is actually wired up.
 */
public final class FabricModJson {
    private final String id;
    private final String version;
    private final String name;
    private final String description;
    private final String license;
    private final String environment;
    private final String accessWidener;
    private final Map<String, List<Entrypoint>> entrypoints;
    private final List<MixinConfig> mixins;
    private final List<Dependency> dependencies;
    private final List<String> nestedJars;

    private FabricModJson(String id, String version, String name, String description, String license,
                          String environment, String accessWidener, Map<String, List<Entrypoint>> entrypoints,
                          List<MixinConfig> mixins, List<Dependency> dependencies, List<String> nestedJars) {
        this.id = id;
        this.version = version;
        this.name = name;
        this.description = description;
        this.license = license;
        this.environment = environment;
        this.accessWidener = accessWidener;
        this.entrypoints = entrypoints;
        this.mixins = mixins;
        this.dependencies = dependencies;
        this.nestedJars = nestedJars;
    }

    public String id() { return id; }
    public String version() { return version; }
    public String name() { return name; }
    public String description() { return description; }
    public String license() { return license; }
    /** One of {@code *}, {@code client}, {@code server}. */
    public String environment() { return environment; }
    /** Path of the access widener file inside the jar, or {@code null}. */
    public String accessWidener() { return accessWidener; }
    public Map<String, List<Entrypoint>> entrypoints() { return entrypoints; }
    public List<MixinConfig> mixins() { return mixins; }
    public List<Dependency> dependencies() { return dependencies; }
    public List<String> nestedJars() { return nestedJars; }

    public List<Entrypoint> entrypoints(String key) {
        return entrypoints.getOrDefault(key, List.of());
    }

    public static FabricModJson read(Reader reader) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("fabric.mod.json is not a JSON object", e);
        }

        String id = string(root, "id", null);
        if (id == null || id.isBlank()) {
            throw new IOException("fabric.mod.json is missing the required \"id\" field");
        }

        String version = string(root, "version", "0.0.0");
        // "${version}" survives in jars built without Loom's expansion; Maven cannot parse it.
        if (version.contains("$")) {
            version = "0.0.0";
        }

        return new FabricModJson(
                id,
                version,
                string(root, "name", id),
                string(root, "description", ""),
                readLicense(root),
                string(root, "environment", "*"),
                string(root, "accessWidener", null),
                readEntrypoints(root),
                readMixins(root),
                readDependencies(root),
                readNestedJars(root));
    }

    private static String readLicense(JsonObject root) {
        JsonElement license = root.get("license");
        if (license == null || license.isJsonNull()) return "";
        if (license.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : license.getAsJsonArray()) {
                if (e.isJsonPrimitive()) parts.add(e.getAsString());
            }
            return String.join(", ", parts);
        }
        return license.isJsonPrimitive() ? license.getAsString() : "";
    }

    /**
     * Entrypoints map an entrypoint key ("main", "client", "server", ...) to a list of targets.
     * Each target is either a bare class name or an object with an explicit language adapter.
     */
    private static Map<String, List<Entrypoint>> readEntrypoints(JsonObject root) {
        Map<String, List<Entrypoint>> result = new LinkedHashMap<>();
        JsonObject obj = object(root, "entrypoints");
        if (obj == null) return Map.copyOf(result);

        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<Entrypoint> targets = new ArrayList<>();
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    targets.add(new Entrypoint("default", element.getAsString()));
                } else if (element.isJsonObject()) {
                    JsonObject eo = element.getAsJsonObject();
                    String value = string(eo, "value", null);
                    if (value != null) {
                        targets.add(new Entrypoint(string(eo, "adapter", "default"), value));
                    }
                }
            }
            if (!targets.isEmpty()) result.put(entry.getKey(), List.copyOf(targets));
        }
        return Map.copyOf(result);
    }

    /** Mixin entries are either a config path or an object carrying an environment restriction. */
    private static List<MixinConfig> readMixins(JsonObject root) {
        List<MixinConfig> result = new ArrayList<>();
        JsonArray array = array(root, "mixins");
        if (array == null) return List.of();

        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                result.add(new MixinConfig(element.getAsString(), "*"));
            } else if (element.isJsonObject()) {
                JsonObject mo = element.getAsJsonObject();
                String config = string(mo, "config", null);
                if (config != null) {
                    result.add(new MixinConfig(config, string(mo, "environment", "*")));
                }
            }
        }
        return List.copyOf(result);
    }

    private static List<Dependency> readDependencies(JsonObject root) {
        List<Dependency> result = new ArrayList<>();
        readDependencyBlock(root, "depends", Dependency.Kind.REQUIRED, result);
        readDependencyBlock(root, "recommends", Dependency.Kind.OPTIONAL, result);
        readDependencyBlock(root, "suggests", Dependency.Kind.OPTIONAL, result);
        readDependencyBlock(root, "breaks", Dependency.Kind.INCOMPATIBLE, result);
        readDependencyBlock(root, "conflicts", Dependency.Kind.DISCOURAGED, result);
        return List.copyOf(result);
    }

    private static void readDependencyBlock(JsonObject root, String key, Dependency.Kind kind, List<Dependency> out) {
        JsonObject block = object(root, key);
        if (block == null) return;

        for (Map.Entry<String, JsonElement> entry : block.entrySet()) {
            List<String> ranges = new ArrayList<>();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                ranges.add(value.getAsString());
            } else if (value.isJsonArray()) {
                for (JsonElement e : value.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) ranges.add(e.getAsString());
                }
            }
            if (ranges.isEmpty()) ranges.add("*");
            out.add(new Dependency(entry.getKey(), ranges, kind));
        }
    }

    private static List<String> readNestedJars(JsonObject root) {
        JsonArray array = array(root, "jars");
        if (array == null) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                String file = string(element.getAsJsonObject(), "file", null);
                if (file != null) result.add(file);
            }
        }
        return List.copyOf(result);
    }

    private static String string(JsonObject obj, String key, String fallback) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }

    private static JsonObject object(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray array(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** A single entrypoint target. {@code adapter} is "default" for plain Java classes. */
    public record Entrypoint(String adapter, String value) {}

    /** A mixin config file plus the environment it applies to ({@code *}, {@code client}, {@code server}). */
    public record MixinConfig(String config, String environment) {}

    /** A declared dependency, normalized across Fabric's five dependency blocks. */
    public record Dependency(String modId, List<String> versionRanges, Kind kind) {
        public enum Kind { REQUIRED, OPTIONAL, INCOMPATIBLE, DISCOURAGED }
    }
}
