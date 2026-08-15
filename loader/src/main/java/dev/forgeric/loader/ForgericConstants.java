package dev.forgeric.loader;

/**
 * Shared constants. Kept in one place because several of these strings are contracts
 * with NeoForge (service names, metadata keys) and must not drift apart.
 */
public final class ForgericConstants {
    private ForgericConstants() {}

    /** Name reported by our {@code IModLanguageLoader}, and written as {@code modLoader} into the synthesized metadata. */
    public static final String LANGUAGE_LOADER = "forgeric-fabric";

    /** Fabric's mod metadata file. Presence of this is what makes a jar ours to handle. */
    public static final String FABRIC_MOD_JSON = "fabric.mod.json";

    /** NeoForge's own metadata file. A jar carrying both is a multi-loader jar and belongs to NeoForge, not us. */
    public static final String NEOFORGE_MODS_TOML = "META-INF/neoforge.mods.toml";

    /** Mod id Fabric mods depend on to require a loader version. */
    public static final String FABRIC_LOADER_MOD_ID = "fabricloader";

    /** Fabric mods declare a Minecraft dependency under this id. */
    public static final String MINECRAFT_MOD_ID = "minecraft";

    /** Java system property set by the installer so the loader can find its profile. */
    public static final String PROFILE_PROPERTY = "forgeric.profile";
}
