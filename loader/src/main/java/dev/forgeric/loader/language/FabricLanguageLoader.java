package dev.forgeric.loader.language;

import dev.forgeric.loader.ForgericConstants;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.language.ModFileScanData;

/**
 * The language loader Fabric mods are routed to.
 *
 * <p>{@link dev.forgeric.loader.metadata.FabricMetadataConverter} writes
 * {@code modLoader = "forgeric-fabric"} into every synthesized mod metadata, and NeoForge uses
 * that string to pick a loader by {@link #name()}. That is the whole handshake: NeoForge owns
 * discovery, resolution and load order, and delegates construction of these particular mods here.
 */
public class FabricLanguageLoader implements IModLanguageLoader {
    @Override
    public String name() {
        return ForgericConstants.LANGUAGE_LOADER;
    }

    @Override
    public String version() {
        String implementation = FabricLanguageLoader.class.getPackage().getImplementationVersion();
        return implementation != null ? implementation : "0.1.0";
    }

    @Override
    public ModContainer loadMod(IModInfo info, ModFileScanData scanData, ModuleLayer layer) throws ModLoadingException {
        return new FabricModContainer(info, layer);
    }
}
