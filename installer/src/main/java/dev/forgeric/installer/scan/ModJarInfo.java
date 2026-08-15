package dev.forgeric.installer.scan;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * What a scan learned about one jar in the mods folder.
 *
 * @param path          the jar itself
 * @param modId         declared mod id, or null when the jar declares no metadata at all
 * @param version       declared version, may be null
 * @param displayName   human-readable name, falls back to the mod id
 * @param kind          which loader(s) the jar declares support for
 * @param requiresFabricApi whether it declares a required dependency on fabric-api
 * @param languageAdapters non-default entrypoint adapters it needs (kotlin, scala, ...)
 * @param mixinTargets  fully-qualified classes its mixins modify
 * @param overwriteTargets subset of mixinTargets where a mixin uses {@code @Overwrite},
 *                      which is the form that genuinely cannot coexist with another mod
 * @param nestedJars    bundled jars (JiJ), which can duplicate libraries another mod also ships
 */
public record ModJarInfo(
        Path path,
        String modId,
        String version,
        String displayName,
        Kind kind,
        boolean requiresFabricApi,
        Set<String> languageAdapters,
        Set<String> mixinTargets,
        Set<String> overwriteTargets,
        List<String> nestedJars) {

    public enum Kind {
        /** Declares fabric.mod.json only. Forgeric bridges this one. */
        FABRIC,
        /** Declares neoforge.mods.toml only. Runs natively, untouched by Forgeric. */
        NEOFORGE,
        /** Declares both. NeoForge takes it; the bridge steps aside. */
        MULTI_LOADER,
        /** No mod metadata: a plain library, or something that does not belong in mods/. */
        UNKNOWN
    }

    public String label() {
        return displayName != null ? displayName : (modId != null ? modId : path.getFileName().toString());
    }

    public boolean isFabricSide() {
        return kind == Kind.FABRIC;
    }
}
