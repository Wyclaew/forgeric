package dev.forgeric.loader.language;

import dev.forgeric.loader.discovery.FabricModRegistry;
import dev.forgeric.loader.metadata.FabricModJson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforgespi.language.IModInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Runs one Fabric mod inside NeoForge's mod lifecycle.
 *
 * <p>The mapping between the two lifecycles is deliberately narrow. Fabric invokes entrypoints
 * once, just before the game starts; NeoForge's closest equivalent is mod construction, so that
 * is where {@code main} / {@code client} / {@code server} entrypoints fire. Everything a Fabric
 * mod does after that — registering callbacks, listening for events — depends on the Fabric API
 * bridge, which does not exist yet (see ARCHITECTURE.md §8). Mods that only use Mixins and plain
 * Minecraft classes work today; mods that call into {@code fabric-api} will fail here, loudly.
 */
public class FabricModContainer extends ModContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Forgeric/Mod");

    /** Fabric's standard entrypoint keys. Custom keys are only invoked by whoever defines them. */
    private static final String ENTRYPOINT_MAIN = "main";
    private static final String ENTRYPOINT_CLIENT = "client";
    private static final String ENTRYPOINT_SERVER = "server";

    private final IEventBus eventBus;
    private final Module module;
    private final FabricModJson metadata;

    public FabricModContainer(IModInfo info, ModuleLayer layer) {
        super(info);
        this.metadata = FabricModRegistry.byNeoForgeId(info.getModId());
        if (metadata == null) {
            throw new ModLoadingException(ModLoadingIssue.error(
                    "Forgeric lost the Fabric metadata for " + info.getModId()).withAffectedMod(info));
        }

        this.eventBus = BusBuilder.builder()
                .markerType(IModBusEvent.class)
                .allowPerPhasePost()
                .build();

        String fileId = info.getOwningFile().getFile().getId();
        this.module = layer.findModule(fileId).orElseThrow(() -> new ModLoadingException(
                ModLoadingIssue.error("Forgeric could not find module " + fileId).withAffectedMod(info)));
    }

    @Override
    protected void constructMod() {
        invokeEntrypoints(ENTRYPOINT_MAIN, ModInitializer.class, "onInitialize");
        if (ForgericSide.isClient()) {
            invokeEntrypoints(ENTRYPOINT_CLIENT, ClientModInitializer.class, "onInitializeClient");
        } else {
            invokeEntrypoints(ENTRYPOINT_SERVER, DedicatedServerModInitializer.class, "onInitializeServer");
        }
    }

    /**
     * Instantiates each declared entrypoint and calls its initializer method.
     *
     * <p>Two target forms exist in the wild: a class implementing one of Fabric's initializer
     * interfaces, and a {@code com.example.Mod::method} static method reference. Both are handled;
     * a target using a non-default language adapter (Kotlin, Scala) is skipped with a warning,
     * since adapters are themselves mods that Forgeric cannot run yet.
     */
    private void invokeEntrypoints(String key, Class<?> initializerType, String initializerMethod) {
        List<FabricModJson.Entrypoint> entrypoints = metadata.entrypoints(key);
        if (entrypoints.isEmpty()) {
            return;
        }

        for (FabricModJson.Entrypoint entrypoint : entrypoints) {
            if (!"default".equals(entrypoint.adapter())) {
                LOGGER.warn("Skipping {} entrypoint '{}' of {}: language adapter '{}' is not supported yet",
                        key, entrypoint.value(), getModId(), entrypoint.adapter());
                continue;
            }

            try {
                if (entrypoint.value().contains("::")) {
                    invokeMethodReference(entrypoint.value());
                } else {
                    invokeInitializerClass(entrypoint.value(), initializerType, initializerMethod);
                }
            } catch (Throwable t) {
                LOGGER.error("Fabric mod {} failed in its '{}' entrypoint ({})",
                        getModId(), key, entrypoint.value(), t);
                throw new ModLoadingException(ModLoadingIssue
                        .error("Fabric entrypoint failed: " + entrypoint.value())
                        .withCause(t)
                        .withAffectedMod(modInfo));
            }
        }
    }

    private void invokeInitializerClass(String className, Class<?> initializerType, String initializerMethod)
            throws ReflectiveOperationException {
        Class<?> clazz = Class.forName(module, className);
        if (clazz == null) {
            throw new ClassNotFoundException("Entrypoint class '" + className + "' not found in " + getModId());
        }

        Object instance = clazz.getDeclaredConstructor().newInstance();
        if (!initializerType.isInstance(instance)) {
            LOGGER.warn("Entrypoint {} of {} does not implement {}; skipping",
                    className, getModId(), initializerType.getSimpleName());
            return;
        }

        Method method = initializerType.getMethod(initializerMethod);
        method.invoke(instance);
        LOGGER.debug("Ran {}.{}() for {}", className, initializerMethod, getModId());
    }

    /** Handles the {@code com.example.Mod::staticMethod} entrypoint form. */
    private void invokeMethodReference(String target) throws ReflectiveOperationException {
        int split = target.indexOf("::");
        String className = target.substring(0, split);
        String methodName = target.substring(split + 2);

        Class<?> clazz = Class.forName(module, className);
        if (clazz == null) {
            throw new ClassNotFoundException("Entrypoint class '" + className + "' not found in " + getModId());
        }

        Method method = clazz.getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(null);
        LOGGER.debug("Ran {}::{} for {}", className, methodName, getModId());
    }

    @Override
    public IEventBus getEventBus() {
        return eventBus;
    }
}
