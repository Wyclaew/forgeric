package dev.forgeric.loader.language;

import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Answers "are we the client or the server?" without depending on NeoForge's {@code Dist}.
 *
 * <p>{@code net.neoforged.api.distmarker.Dist} is not published as a standalone compile
 * artifact — it only reaches mods through NeoForge's userdev pipeline, which would drag the
 * whole moddev Gradle plugin into this build. Mixin ships the same information and is already
 * on the compile classpath for both loaders, so it is the cheaper source of truth.
 *
 * <p>Safe to call from mod construction onwards; Mixin is fully bootstrapped well before then.
 */
public final class ForgericSide {
    private ForgericSide() {}

    public static boolean isClient() {
        return MixinEnvironment.getDefaultEnvironment().getSide() == MixinEnvironment.Side.CLIENT;
    }

    public static boolean isDedicatedServer() {
        return MixinEnvironment.getDefaultEnvironment().getSide() == MixinEnvironment.Side.SERVER;
    }

    /** {@return the Fabric environment string this side corresponds to: "client" or "server"} */
    public static String fabricEnvironment() {
        return isClient() ? "client" : "server";
    }

    /** {@return true if a Fabric {@code environment} declaration applies to the running side} */
    public static boolean matches(String environment) {
        if (environment == null || environment.isBlank() || "*".equals(environment)) {
            return true;
        }
        return environment.equals(fabricEnvironment());
    }
}
