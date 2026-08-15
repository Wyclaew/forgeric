package dev.forgeric.installer.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Operating-system specifics: where launchers keep their data, and what Java we are on.
 *
 * <p>Paths are probed rather than assumed. Users move launcher data, and Prism in particular
 * supports a portable layout where everything sits next to the executable, so the installer
 * offers what it can actually find and falls back to asking.
 */
public final class Platform {
    private Platform() {}

    public enum Os { MACOS, WINDOWS, LINUX, UNKNOWN }

    public static Os current() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) return Os.MACOS;
        if (name.contains("win")) return Os.WINDOWS;
        if (name.contains("nux") || name.contains("nix")) return Os.LINUX;
        return Os.UNKNOWN;
    }

    public static boolean isMac() { return current() == Os.MACOS; }
    public static boolean isWindows() { return current() == Os.WINDOWS; }

    public static int javaMajor() {
        String version = System.getProperty("java.version", "0");
        int dot = version.indexOf('.');
        String major = dot > 0 ? version.substring(0, dot) : version;
        int dash = major.indexOf('-');
        if (dash > 0) major = major.substring(0, dash);
        try {
            return Integer.parseInt(major);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Path home() {
        return Paths.get(System.getProperty("user.home", "."));
    }

    /**
     * {@return every Prism Launcher data directory that exists on this machine}
     * Ordered most-likely-first. Empty when Prism is not installed.
     */
    public static List<Path> prismDataDirs() {
        List<Path> candidates = new ArrayList<>();
        switch (current()) {
            case MACOS -> {
                candidates.add(home().resolve("Library/Application Support/PrismLauncher"));
                candidates.add(home().resolve("Library/Application Support/PolyMC"));
            }
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                if (appData != null) {
                    candidates.add(Paths.get(appData, "PrismLauncher"));
                    candidates.add(Paths.get(appData, "PolyMC"));
                }
                // Portable installs keep an "instances" folder beside the executable.
                candidates.add(Paths.get("C:", "PrismLauncher"));
            }
            case LINUX -> {
                candidates.add(home().resolve(".local/share/PrismLauncher"));
                candidates.add(home().resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"));
                candidates.add(home().resolve(".local/share/PolyMC"));
            }
            default -> {}
        }

        List<Path> found = new ArrayList<>();
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("instances"))) {
                found.add(candidate);
            }
        }
        return found;
    }

    /** {@return the vanilla launcher's game directory, whether or not it exists yet} */
    public static Path vanillaGameDir() {
        return switch (current()) {
            case MACOS -> home().resolve("Library/Application Support/minecraft");
            case WINDOWS -> {
                String appData = System.getenv("APPDATA");
                yield appData != null ? Paths.get(appData, ".minecraft") : home().resolve(".minecraft");
            }
            default -> home().resolve(".minecraft");
        };
    }
}
