package dev.forgeric.loader.discovery;

import dev.forgeric.loader.metadata.FabricModJson;
import dev.forgeric.loader.metadata.ForgericIds;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the parsed {@code fabric.mod.json} for every Fabric mod discovered this run.
 *
 * <p>Needed because NeoForge's {@code IModInfo} has no room for Fabric-specific declarations:
 * entrypoints, access wideners and nested jars all survive metadata conversion only if kept
 * alongside. Discovery fills this in; the language loader reads it back when it constructs
 * containers.
 *
 * <p>Keyed by the sanitized NeoForge id, since that is the id NeoForge hands back later.
 */
public final class FabricModRegistry {
    private FabricModRegistry() {}

    private static final Map<String, FabricModJson> BY_NEOFORGE_ID = new ConcurrentHashMap<>();

    public static void register(FabricModJson mod) {
        BY_NEOFORGE_ID.put(ForgericIds.toNeoForge(mod.id()), mod);
    }

    /** {@return the metadata for a mod, or null if NeoForge is asking about a non-Fabric mod} */
    public static FabricModJson byNeoForgeId(String neoForgeId) {
        return BY_NEOFORGE_ID.get(neoForgeId);
    }

    public static FabricModJson byFabricId(String fabricId) {
        return BY_NEOFORGE_ID.get(ForgericIds.toNeoForge(fabricId));
    }

    public static boolean isFabricMod(String neoForgeId) {
        return BY_NEOFORGE_ID.containsKey(neoForgeId);
    }

    public static Collection<FabricModJson> all() {
        return Map.copyOf(BY_NEOFORGE_ID).values();
    }

    public static int count() {
        return BY_NEOFORGE_ID.size();
    }
}
