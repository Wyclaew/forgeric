package dev.forgeric.installer.scan;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads a mods folder and reports what is actually inside each jar.
 *
 * <p>Everything is derived from the jars themselves rather than from a curated list of known
 * mods. A hardcoded list goes stale the moment a mod updates, and would only ever cover mods
 * someone remembered to add; reading the metadata works for any mod, including private ones.
 */
public final class ModScanner {
    private static final String FABRIC_MOD_JSON = "fabric.mod.json";
    private static final String NEOFORGE_MODS_TOML = "META-INF/neoforge.mods.toml";

    private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String OVERWRITE_ANNOTATION = "Lorg/spongepowered/asm/mixin/Overwrite;";

    private ModScanner() {}

    public static List<ModJarInfo> scan(Path modsDir) throws IOException {
        List<ModJarInfo> results = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            return results;
        }

        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
        }

        for (Path jar : jars) {
            try {
                results.add(read(jar));
            } catch (IOException e) {
                // A jar we cannot open is still worth reporting, rather than aborting the scan.
                results.add(new ModJarInfo(jar, null, null, jar.getFileName().toString(),
                        ModJarInfo.Kind.UNKNOWN, false, Set.of(), Set.of(), Set.of(), List.of()));
            }
        }
        return results;
    }

    public static ModJarInfo read(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            boolean hasFabric = jar.getEntry(FABRIC_MOD_JSON) != null;
            boolean hasNeoForge = jar.getEntry(NEOFORGE_MODS_TOML) != null;

            ModJarInfo.Kind kind;
            if (hasFabric && hasNeoForge) {
                kind = ModJarInfo.Kind.MULTI_LOADER;
            } else if (hasFabric) {
                kind = ModJarInfo.Kind.FABRIC;
            } else if (hasNeoForge) {
                kind = ModJarInfo.Kind.NEOFORGE;
            } else {
                kind = ModJarInfo.Kind.UNKNOWN;
            }

            if (kind == ModJarInfo.Kind.UNKNOWN) {
                return new ModJarInfo(path, null, null, path.getFileName().toString(),
                        kind, false, Set.of(), Set.of(), Set.of(), List.of());
            }

            // Prefer Fabric metadata when present: it is the side Forgeric has to reason about,
            // and it is where entrypoints and mixin configs are declared.
            return hasFabric ? readFabric(jar, path, kind) : readNeoForge(jar, path, kind);
        }
    }

    private static ModJarInfo readFabric(JarFile jar, Path path, ModJarInfo.Kind kind) throws IOException {
        JsonObject root;
        try (InputStream in = jar.getInputStream(jar.getEntry(FABRIC_MOD_JSON));
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("unreadable fabric.mod.json", e);
        }

        String modId = string(root, "id");
        String version = string(root, "version");
        String name = string(root, "name");

        boolean requiresFabricApi = false;
        JsonElement depends = root.get("depends");
        if (depends != null && depends.isJsonObject()) {
            for (String key : depends.getAsJsonObject().keySet()) {
                // Fabric API is published as one umbrella id plus many per-module ids.
                if (key.equals("fabric-api") || key.equals("fabric") || key.startsWith("fabric-")) {
                    requiresFabricApi = true;
                    break;
                }
            }
        }

        Set<String> adapters = new LinkedHashSet<>();
        JsonElement entrypoints = root.get("entrypoints");
        if (entrypoints != null && entrypoints.isJsonObject()) {
            for (var entry : entrypoints.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (element.isJsonObject()) {
                        String adapter = string(element.getAsJsonObject(), "adapter");
                        if (adapter != null && !adapter.equals("default")) {
                            adapters.add(adapter);
                        }
                    }
                }
            }
        }

        List<String> mixinConfigs = new ArrayList<>();
        JsonElement mixins = root.get("mixins");
        if (mixins != null && mixins.isJsonArray()) {
            for (JsonElement element : mixins.getAsJsonArray()) {
                if (element.isJsonPrimitive()) {
                    mixinConfigs.add(element.getAsString());
                } else if (element.isJsonObject()) {
                    String config = string(element.getAsJsonObject(), "config");
                    if (config != null) mixinConfigs.add(config);
                }
            }
        }

        MixinScan mixinScan = merge(scanMixins(jar, mixinConfigs), scanNestedJars(jar));

        List<String> nested = new ArrayList<>();
        JsonElement jars = root.get("jars");
        if (jars != null && jars.isJsonArray()) {
            for (JsonElement element : jars.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    String file = string(element.getAsJsonObject(), "file");
                    if (file != null) nested.add(file);
                }
            }
        }

        return new ModJarInfo(path, modId, version, name != null ? name : modId, kind,
                requiresFabricApi, adapters, mixinScan.targets, mixinScan.overwrites, nested);
    }

    private static ModJarInfo readNeoForge(JarFile jar, Path path, ModJarInfo.Kind kind) throws IOException {
        String modId = null;
        String version = null;
        String name = null;
        List<String> mixinConfigs = new ArrayList<>();

        try (InputStream in = jar.getInputStream(jar.getEntry(NEOFORGE_MODS_TOML));
             var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            UnmodifiableConfig config = TomlFormat.instance().createParser().parse(reader).unmodifiable();

            List<UnmodifiableConfig> mods = config.getOrElse("mods", List.<UnmodifiableConfig>of());
            if (!mods.isEmpty()) {
                UnmodifiableConfig first = mods.getFirst();
                modId = first.get("modId");
                version = first.get("version");
                name = first.get("displayName");
            }
            List<UnmodifiableConfig> mixins = config.getOrElse("mixins", List.<UnmodifiableConfig>of());
            for (UnmodifiableConfig mixin : mixins) {
                String value = mixin.get("config");
                if (value != null) mixinConfigs.add(value);
            }
        } catch (Exception e) {
            throw new IOException("unreadable neoforge.mods.toml: " + e.getMessage(), e);
        }

        MixinScan mixinScan = merge(scanMixins(jar, mixinConfigs), scanNestedJars(jar));

        // A wrapper jar keeps its real content in META-INF/jarjar; report those as bundled too.
        List<String> nested = jar.stream()
                .map(JarEntry::getName)
                .filter(n -> n.startsWith("META-INF/jarjar/") && n.endsWith(".jar"))
                .toList();

        return new ModJarInfo(path, modId, version, name != null ? name : modId, kind,
                false, Set.of(), mixinScan.targets, mixinScan.overwrites, nested);
    }

    private static MixinScan merge(MixinScan a, MixinScan b) {
        Set<String> targets = new LinkedHashSet<>(a.targets);
        targets.addAll(b.targets);
        Set<String> overwrites = new LinkedHashSet<>(a.overwrites);
        overwrites.addAll(b.overwrites);
        return new MixinScan(targets, overwrites);
    }

    private record MixinScan(Set<String> targets, Set<String> overwrites) {}

    /** Guards against a pathological jar exhausting memory while being read into a map. */
    private static final long MAX_NESTED_JAR_BYTES = 96L * 1024 * 1024;

    /**
     * Reads mixin targets out of jars bundled inside this one (JarJar / Jar-in-Jar).
     *
     * <p>Necessary because several NeoForge mods ship an outer jar that is only a wrapper:
     * Sodium's NeoForge build, for instance, declares its mixin configs in mods.toml but keeps
     * every class and config inside {@code META-INF/jarjar/...}. Scanning only the outer jar
     * reports zero patched classes for mods that patch dozens, which would quietly disable
     * every cross-loader check on the NeoForge side.
     */
    private static MixinScan scanNestedJars(JarFile jar) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> overwrites = new LinkedHashSet<>();

        // NeoForge bundles under META-INF/jarjar/, Fabric under META-INF/jars/.
        List<JarEntry> nested = jar.stream()
                .filter(e -> e.getName().endsWith(".jar"))
                .filter(e -> e.getName().startsWith("META-INF/jarjar/")
                        || e.getName().startsWith("META-INF/jars/"))
                .toList();

        for (JarEntry entry : nested) {
            if (entry.getSize() > MAX_NESTED_JAR_BYTES) continue;

            Map<String, byte[]> contents = new LinkedHashMap<>();
            long total = 0;
            try (ZipInputStream zin = new ZipInputStream(jar.getInputStream(entry))) {
                ZipEntry inner;
                while ((inner = zin.getNextEntry()) != null) {
                    if (inner.isDirectory()) continue;
                    String name = inner.getName();
                    // Only what the scan needs: mixin configs, metadata, and class files.
                    if (!name.endsWith(".json") && !name.endsWith(".class") && !name.endsWith(".toml")) {
                        continue;
                    }
                    byte[] bytes = zin.readAllBytes();
                    total += bytes.length;
                    if (total > MAX_NESTED_JAR_BYTES) break;
                    contents.put(name, bytes);
                }
            } catch (Exception e) {
                continue; // unreadable nested jar: skip, keep scanning
            }

            scanMixinsFromMap(contents, targets, overwrites);
        }
        return new MixinScan(targets, overwrites);
    }

    /**
     * Same resolution as {@link #scanMixins}, but over an in-memory jar.
     *
     * <p>Mixin configs are discovered by looking for *.mixins.json rather than by reading the
     * nested jar's metadata: the declaration usually lives in the outer jar's mods.toml, so the
     * nested jar has no list of its own to follow.
     */
    private static void scanMixinsFromMap(Map<String, byte[]> contents,
                                          Set<String> targets, Set<String> overwrites) {
        for (var entry : contents.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".json") || name.contains("/")) {
                continue; // mixin configs sit at the jar root
            }

            JsonObject config;
            try (var reader = new InputStreamReader(
                    new java.io.ByteArrayInputStream(entry.getValue()), StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) continue;
                config = parsed.getAsJsonObject();
            } catch (Exception e) {
                continue;
            }

            String pkg = string(config, "package");
            if (pkg == null || !config.has("mixins") && !config.has("client") && !config.has("server")) {
                continue; // not a mixin config
            }

            List<String> mixinClasses = new ArrayList<>();
            for (String section : new String[] {"mixins", "client", "server"}) {
                JsonElement element = config.get(section);
                if (element != null && element.isJsonArray()) {
                    for (JsonElement item : (JsonArray) element) {
                        if (item.isJsonPrimitive()) mixinClasses.add(item.getAsString());
                    }
                }
            }

            for (String mixinClass : mixinClasses) {
                byte[] bytecode = contents.get((pkg + "." + mixinClass).replace('.', '/') + ".class");
                if (bytecode == null) continue;
                try {
                    MixinClassVisitor visitor = new MixinClassVisitor();
                    new ClassReader(bytecode).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    targets.addAll(visitor.targets);
                    if (visitor.hasOverwrite) {
                        overwrites.addAll(visitor.targets);
                    }
                } catch (Exception e) {
                    // Unparseable mixin class: skip it.
                }
            }
        }
    }

    /**
     * Resolves declared mixin configs to the classes they actually modify.
     *
     * <p>A mixin config only names mixin classes; the classes they target live in the
     * {@code @Mixin} annotation on each one, so the bytecode has to be read to learn them.
     * Failures here are swallowed on purpose: an unreadable mixin should degrade the report,
     * never fail the scan.
     */
    private static MixinScan scanMixins(JarFile jar, List<String> mixinConfigs) {
        Set<String> targets = new LinkedHashSet<>();
        Set<String> overwrites = new LinkedHashSet<>();

        for (String configPath : mixinConfigs) {
            JarEntry configEntry = jar.getJarEntry(configPath);
            if (configEntry == null) continue;

            JsonObject config;
            try (InputStream in = jar.getInputStream(configEntry);
                 var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                config = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (Exception e) {
                continue;
            }

            String pkg = string(config, "package");
            if (pkg == null) continue;

            List<String> mixinClasses = new ArrayList<>();
            for (String section : new String[] {"mixins", "client", "server"}) {
                JsonElement element = config.get(section);
                if (element != null && element.isJsonArray()) {
                    for (JsonElement item : (JsonArray) element) {
                        if (item.isJsonPrimitive()) mixinClasses.add(item.getAsString());
                    }
                }
            }

            for (String mixinClass : mixinClasses) {
                String entryName = (pkg + "." + mixinClass).replace('.', '/') + ".class";
                JarEntry classEntry = jar.getJarEntry(entryName);
                if (classEntry == null) continue;

                try (InputStream in = jar.getInputStream(classEntry)) {
                    MixinClassVisitor visitor = new MixinClassVisitor();
                    new ClassReader(in).accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    targets.addAll(visitor.targets);
                    if (visitor.hasOverwrite) {
                        overwrites.addAll(visitor.targets);
                    }
                } catch (Exception e) {
                    // Unparseable mixin class: skip it, keep scanning.
                }
            }
        }
        return new MixinScan(targets, overwrites);
    }

    /** Pulls {@code @Mixin} targets and notes whether the class contains an {@code @Overwrite}. */
    private static final class MixinClassVisitor extends ClassVisitor {
        private final Set<String> targets = new LinkedHashSet<>();
        private boolean hasOverwrite;

        MixinClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!MIXIN_ANNOTATION.equals(descriptor)) {
                return null;
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitArray(String name) {
                    // "value" holds Class literals, "targets" holds strings for inaccessible classes.
                    boolean isValue = "value".equals(name);
                    boolean isTargets = "targets".equals(name);
                    if (!isValue && !isTargets) return null;

                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String unusedName, Object value) {
                            if (isValue && value instanceof Type type) {
                                targets.add(type.getClassName());
                            } else if (isTargets && value instanceof String target) {
                                targets.add(target.replace('/', '.'));
                            }
                        }
                    };
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    if (OVERWRITE_ANNOTATION.equals(annotationDescriptor)) {
                        hasOverwrite = true;
                    }
                    return null;
                }
            };
        }
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
