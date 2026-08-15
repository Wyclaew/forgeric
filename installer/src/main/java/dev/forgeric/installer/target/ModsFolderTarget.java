package dev.forgeric.installer.target;

import dev.forgeric.installer.core.InstallLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs the bridge straight into a mods folder.
 *
 * <p>The fallback that works everywhere: the vanilla launcher, MultiMC, ATLauncher, a server
 * directory, or any setup this installer does not know about. It assumes NeoForge is already
 * installed there — Forgeric extends NeoForge rather than replacing it, and reinstalling
 * NeoForge on the user's behalf would be a much larger, riskier operation.
 */
public final class ModsFolderTarget {
    private ModsFolderTarget() {}

    /**
     * @param gameDir the game directory (the one containing {@code mods}), or the mods folder itself
     */
    public static Path install(Path gameDir, Path loaderJar, InstallLog log) throws IOException {
        Path modsDir = resolveModsDir(gameDir);
        Files.createDirectories(modsDir);

        removeStaleInstalls(modsDir, log);

        Path destination = modsDir.resolve(loaderJar.getFileName().toString());
        Files.copy(loaderJar, destination, StandardCopyOption.REPLACE_EXISTING);
        log.info("Installed " + destination.getFileName() + " into " + modsDir);

        if (!looksLikeNeoForgeInstall(modsDir.getParent())) {
            log.warn("Could not confirm NeoForge is installed in " + modsDir.getParent()
                    + ". Forgeric only runs on top of NeoForge — install NeoForge "
                    + "for this Minecraft version first, or nothing will load.");
        }
        return destination;
    }

    /** Accepts either a game directory or the mods folder directly, since users pass both. */
    private static Path resolveModsDir(Path input) {
        return input.getFileName() != null && input.getFileName().toString().equals("mods")
                ? input
                : input.resolve("mods");
    }

    private static void removeStaleInstalls(Path modsDir, InstallLog log) throws IOException {
        if (!Files.isDirectory(modsDir)) return;

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
    }

    /**
     * A weak but useful check: NeoForge installs leave their libraries under the game directory.
     * Only used to warn, never to block — plenty of valid layouts keep libraries elsewhere.
     */
    private static boolean looksLikeNeoForgeInstall(Path gameDir) {
        if (gameDir == null) return false;
        Path neoforgeLibs = gameDir.resolve("libraries/net/neoforged");
        if (Files.isDirectory(neoforgeLibs)) return true;
        // Prism/MultiMC keep libraries outside the instance; treat their marker as good enough.
        return Files.isRegularFile(gameDir.getParent() == null ? gameDir : gameDir.getParent().resolve("mmc-pack.json"));
    }
}
