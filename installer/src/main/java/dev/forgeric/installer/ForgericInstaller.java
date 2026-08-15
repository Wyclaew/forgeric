package dev.forgeric.installer;

import dev.forgeric.installer.core.InstallLog;
import dev.forgeric.installer.core.Payload;
import dev.forgeric.installer.core.Platform;
import dev.forgeric.installer.profile.VersionProfile;
import dev.forgeric.installer.scan.ConflictAnalyzer;
import dev.forgeric.installer.scan.Finding;
import dev.forgeric.installer.scan.ModJarInfo;
import dev.forgeric.installer.scan.ModScanner;
import dev.forgeric.installer.target.ModsFolderTarget;
import dev.forgeric.installer.target.PrismTarget;
import dev.forgeric.installer.ui.InstallerWindow;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Entry point. Double-clicking the jar opens the window; a terminal gets a CLI.
 */
public final class ForgericInstaller {
    /** Newest Minecraft version this build ships a profile for. */
    public static final String DEFAULT_MINECRAFT = "26.2";

    public static void main(String[] args) {
        if (args.length == 0) {
            if (GraphicsEnvironment.isHeadless()) {
                printUsage();
                System.exit(2);
            }
            InstallerWindow.launch();
            return;
        }

        try {
            System.exit(runCli(args));
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int runCli(String[] args) throws IOException {
        String command = args[0];
        if (command.equals("--help") || command.equals("-h") || command.equals("help")) {
            printUsage();
            return 0;
        }
        if (command.equals("list")) {
            return listEnvironment();
        }

        Args parsed = Args.parse(args);
        // Diagnosing a folder must work even when no profile matches, so it runs before the
        // profile and payload are resolved.
        if (command.equals("doctor")) {
            return runDoctor(parsed);
        }

        VersionProfile profile = loadProfile(parsed.minecraft);
        InstallLog log = InstallLog.toStdout();

        if (profile.isObfuscated()) {
            System.err.println("ERROR: Minecraft " + profile.minecraft() + " ships obfuscated, which needs a "
                    + "runtime remapper Forgeric does not have yet. See ARCHITECTURE.md section 8.");
            return 1;
        }

        Path loaderJar = parsed.loaderJar != null ? parsed.loaderJar : Payload.extractLoaderJar();
        if (!Files.isRegularFile(loaderJar)) {
            System.err.println("ERROR: loader jar not found at " + loaderJar);
            return 1;
        }

        log.info("Forgeric " + profile.forgericVersion() + " (" + profile.status() + ")");
        log.info("Target: Minecraft " + profile.minecraft() + " / NeoForge " + profile.neoForgeVersion()
                + " / Fabric Loader " + profile.fabricLoaderVersion());

        Path installedInto;
        switch (command) {
            case "prism" -> installedInto = installPrism(parsed, profile, loaderJar, log);
            case "mods" -> {
                if (parsed.positional == null) {
                    System.err.println("ERROR: 'mods' needs a path. Example: mods ~/.minecraft");
                    return 1;
                }
                ModsFolderTarget.install(parsed.positional, loaderJar, log);
                installedInto = parsed.positional;
            }
            default -> {
                System.err.println("ERROR: unknown command '" + command + "'");
                printUsage();
                return 1;
            }
        }

        printPostInstallNotes(profile, log);
        // A folder that already holds mods is worth checking straight away: mixed packs fail in
        // ways that are much easier to read here than in a crash log.
        if (installedInto != null) {
            checkAfterInstall(installedInto, profile, log);
        }
        return 0;
    }

    /**
     * Inspects a mods folder for the failure modes that mixed Forge/Fabric packs run into.
     *
     * <p>Separate from installing on purpose: it is the command to run after adding mods,
     * which is when conflicts actually appear.
     */
    private static int runDoctor(Args args) throws IOException {
        if (args.positional == null) {
            System.err.println("ERROR: 'doctor' needs a path. Example: doctor ~/.minecraft");
            return 1;
        }

        Path modsDir = resolveModsDir(args.positional);
        if (!Files.isDirectory(modsDir)) {
            System.err.println("ERROR: no mods folder at " + modsDir);
            return 1;
        }

        VersionProfile profile = null;
        try {
            profile = loadProfile(args.minecraft);
        } catch (IOException e) {
            System.out.println("Note: " + e.getMessage());
        }

        System.out.println("Scanning " + modsDir);
        List<ModJarInfo> mods = ModScanner.scan(modsDir);
        if (mods.isEmpty()) {
            System.out.println("No jars found.");
            return 0;
        }

        System.out.println();
        for (ModJarInfo mod : mods) {
            // The patched-class count is the useful number here: it is roughly how much of the
            // game a mod rewrites, and therefore how likely it is to be involved in a conflict.
            String patches = mod.mixinTargets().isEmpty()
                    ? ""
                    : mod.mixinTargets().size() + " classes patched"
                      + (mod.overwriteTargets().isEmpty() ? "" : ", " + mod.overwriteTargets().size() + " overwritten");
            System.out.printf("  %-12s %-26s %-24s %s%n", mod.kind(), mod.label(),
                    mod.version() != null ? mod.version() : "", patches);
        }

        List<Finding> findings = ConflictAnalyzer.analyze(mods, profile);
        System.out.println();
        return printFindings(findings);
    }

    private static void checkAfterInstall(Path gameDir, VersionProfile profile, InstallLog log) {
        try {
            Path modsDir = resolveModsDir(gameDir);
            List<ModJarInfo> mods = ModScanner.scan(modsDir).stream()
                    // The bridge itself is not a mod anyone needs reported back to them.
                    .filter(m -> !m.path().getFileName().toString().startsWith("forgeric-loader"))
                    .toList();
            if (mods.isEmpty()) return;

            List<Finding> findings = ConflictAnalyzer.analyze(mods, profile);
            boolean interesting = findings.stream()
                    .anyMatch(f -> f.severity() != Finding.Severity.INFO);
            if (!interesting) return;

            log.info("");
            log.info("Checked the existing mods in this folder:");
            findings.stream()
                    .filter(f -> f.severity() != Finding.Severity.INFO)
                    .forEach(f -> log.info(f.render()));
            log.info("");
            log.info("Run 'doctor " + gameDir + "' any time for the full report.");
        } catch (IOException e) {
            log.warn("Could not check the mods folder: " + e.getMessage());
        }
    }

    private static int printFindings(List<Finding> findings) {
        long errors = findings.stream().filter(f -> f.severity() == Finding.Severity.ERROR).count();
        long warnings = findings.stream().filter(f -> f.severity() == Finding.Severity.WARNING).count();

        for (Finding finding : findings) {
            System.out.println(finding.render());
            System.out.println();
        }

        if (errors == 0 && warnings == 0) {
            System.out.println("No problems found.");
            return 0;
        }
        System.out.println(errors + " error(s), " + warnings + " warning(s).");
        return errors > 0 ? 1 : 0;
    }

    /** Accepts either a game directory or the mods folder itself, since users pass both. */
    private static Path resolveModsDir(Path input) {
        return input.getFileName() != null && input.getFileName().toString().equals("mods")
                ? input
                : input.resolve("mods");
    }

    /** @return the game directory that was installed into, for the follow-up mods check */
    private static Path installPrism(Args args, VersionProfile profile, Path loaderJar, InstallLog log)
            throws IOException {
        if (args.instance != null) {
            PrismTarget.installIntoInstance(args.instance, profile, loaderJar, log);
            return PrismTarget.resolveMinecraftDir(args.instance);
        }

        if (args.newInstanceName == null) {
            throw new IOException("Specify either --new <name> to create an instance, "
                    + "or --instance <path> to install into an existing one.");
        }

        Path dataDir = args.dataDir;
        if (dataDir == null) {
            List<Path> found = Platform.prismDataDirs();
            if (found.isEmpty()) {
                throw new IOException("Prism Launcher was not found. Pass --data-dir <path> to point at it.");
            }
            dataDir = found.getFirst();
            log.info("Using Prism data directory " + dataDir);
        }
        Path instanceDir = PrismTarget.createInstance(dataDir, args.newInstanceName, profile, loaderJar, log);
        return PrismTarget.resolveMinecraftDir(instanceDir);
    }

    private static void printPostInstallNotes(VersionProfile profile, InstallLog log) {
        log.info("");
        log.info("Done. Put Forge and Fabric mods together in the instance's mods folder.");
        if (!profile.fabricApiSupported()) {
            log.warn("Fabric mods that depend on fabric-api will not load yet — that bridge is "
                    + "not written. Fabric mods using only Mixins and vanilla classes should work.");
        }
        if (log.warnings() > 0) {
            log.info("Finished with " + log.warnings() + " warning(s) above.");
        }
    }

    private static VersionProfile loadProfile(String minecraft) throws IOException {
        return VersionProfile.loadBundled(minecraft != null ? minecraft : DEFAULT_MINECRAFT);
    }

    private static int listEnvironment() {
        System.out.println("Bundled Minecraft profiles:");
        List<String> profiles = VersionProfile.listBundled();
        if (profiles.isEmpty()) {
            System.out.println("  (none — this installer was built without profiles)");
        } else {
            for (String version : profiles) {
                System.out.println("  " + version + (version.equals(DEFAULT_MINECRAFT) ? "  (default)" : ""));
            }
        }

        System.out.println();
        System.out.println("Prism Launcher installations found:");
        List<Path> prism = Platform.prismDataDirs();
        if (prism.isEmpty()) {
            System.out.println("  (none found — pass --data-dir to point at one)");
        } else {
            for (Path path : prism) {
                System.out.println("  " + path);
            }
        }

        System.out.println();
        System.out.println("Running on Java " + Platform.javaMajor() + " / " + Platform.current());
        return 0;
    }

    private static void printUsage() {
        System.out.println("""
                Forgeric installer — run Forge and Fabric mods in one Minecraft instance.

                Usage:
                  forgeric-installer                          open the installer window
                  forgeric-installer list                     show bundled versions and detected launchers
                  forgeric-installer prism --new <name>       create a new Prism instance
                  forgeric-installer prism --instance <path>  install into an existing Prism instance
                  forgeric-installer mods <game-dir>          install into any mods folder
                  forgeric-installer doctor <game-dir>        check a mods folder for conflicts

                Run 'doctor' after adding mods. Forge and Fabric mods were not built to sit in one
                folder, and it catches the failures that are painful to read in a crash log:
                the same mod installed twice, mixins fighting over a class, missing Fabric API.

                Options:
                  --mc <version>        Minecraft version to target (default: %s)
                  --data-dir <path>     Prism data directory, when auto-detection fails
                  --loader-jar <path>   use a loader jar from disk instead of the bundled one

                Forgeric runs on top of NeoForge. For 'mods' installs, install NeoForge first.
                """.formatted(DEFAULT_MINECRAFT));
    }

    /** Minimal flag parsing — the option set is small and unlikely to grow much. */
    private record Args(String minecraft, Path dataDir, Path instance, String newInstanceName,
                        Path loaderJar, Path positional) {

        static Args parse(String[] args) throws IOException {
            String minecraft = null;
            Path dataDir = null;
            Path instance = null;
            String newInstanceName = null;
            Path loaderJar = null;
            Path positional = null;

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--mc" -> minecraft = value(args, ++i, "--mc");
                    case "--data-dir" -> dataDir = Path.of(value(args, ++i, "--data-dir"));
                    case "--instance" -> instance = Path.of(value(args, ++i, "--instance"));
                    case "--new" -> newInstanceName = value(args, ++i, "--new");
                    case "--loader-jar" -> loaderJar = Path.of(value(args, ++i, "--loader-jar"));
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new IOException("Unknown option: " + arg);
                        }
                        positional = Path.of(arg);
                    }
                }
            }
            return new Args(minecraft, dataDir, instance, newInstanceName, loaderJar, positional);
        }

        private static String value(String[] args, int index, String option) throws IOException {
            if (index >= args.length) {
                throw new IOException(option + " needs a value");
            }
            return args[index];
        }
    }
}
