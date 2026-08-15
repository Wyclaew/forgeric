package dev.forgeric.installer.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The analyzer decides what a user is told about their mods folder, so each rule is pinned to a
 * concrete failure mode. Inputs are built by hand rather than from real jars: the rules are pure
 * logic over already-scanned metadata, and jar fixtures would only test the scanner instead.
 */
class ConflictAnalyzerTest {

    private static ModJarInfo mod(String fileName, String modId, ModJarInfo.Kind kind) {
        return new ModJarInfo(Path.of(fileName), modId, "1.0.0", modId, kind,
                false, Set.of(), Set.of(), Set.of(), List.of());
    }

    private static ModJarInfo fabricMod(String modId, boolean needsApi, Set<String> mixinTargets,
                                        Set<String> overwrites) {
        return new ModJarInfo(Path.of(modId + "-fabric.jar"), modId, "1.0.0", modId,
                ModJarInfo.Kind.FABRIC, needsApi, Set.of(), mixinTargets, overwrites, List.of());
    }

    private static ModJarInfo neoForgeMod(String modId, Set<String> mixinTargets, Set<String> overwrites) {
        return new ModJarInfo(Path.of(modId + "-neoforge.jar"), modId, "1.0.0", modId,
                ModJarInfo.Kind.NEOFORGE, false, Set.of(), mixinTargets, overwrites, List.of());
    }

    private static boolean hasFinding(List<Finding> findings, Finding.Severity severity, String titleFragment) {
        return findings.stream().anyMatch(f -> f.severity() == severity && f.title().contains(titleFragment));
    }

    /** The most common way a mixed pack breaks: both loaders' builds of one mod, side by side. */
    @Test
    void reportsTheSameModInstalledForBothLoaders() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                mod("sodium-fabric.jar", "sodium", ModJarInfo.Kind.FABRIC),
                mod("sodium-neoforge.jar", "sodium", ModJarInfo.Kind.NEOFORGE)), null);

        assertTrue(hasFinding(findings, Finding.Severity.ERROR, "'sodium' is installed 2 times"));
        // The NeoForge build is the better one to keep: it runs natively, without the bridge.
        assertTrue(findings.stream().anyMatch(f -> f.suggestion() != null
                && f.suggestion().contains("Keep the NeoForge build")));
    }

    @Test
    void acceptsDistinctModsWithoutComplaint() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                mod("krypton.jar", "krypton", ModJarInfo.Kind.FABRIC),
                mod("jei.jar", "jei", ModJarInfo.Kind.NEOFORGE)), null);

        assertFalse(findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR));
    }

    @Test
    void flagsFabricModsThatNeedTheUnbuiltFabricApi() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("entityculling", true, Set.of(), Set.of()),
                fabricMod("krypton", false, Set.of(), Set.of())), null);

        assertTrue(hasFinding(findings, Finding.Severity.ERROR, "require the Fabric API"));
        Finding finding = findings.stream()
                .filter(f -> f.title().contains("Fabric API")).findFirst().orElseThrow();
        assertTrue(finding.detail().contains("entityculling"));
        assertFalse(finding.detail().contains("krypton"), "a mod without the dependency must not be listed");
    }

    @Test
    void staysQuietAboutFabricApiWhenAProfileSaysItIsSupported() {
        // Guards the future: once the API bridge ships, the profile flips and this must stop firing.
        var supportedProfile = TestProfiles.withFabricApi(true);
        List<Finding> findings = ConflictAnalyzer.analyze(
                List.of(fabricMod("entityculling", true, Set.of(), Set.of())), supportedProfile);

        assertFalse(hasFinding(findings, Finding.Severity.ERROR, "require the Fabric API"));
    }

    @Test
    void flagsUnsupportedLanguageAdapters() {
        ModJarInfo kotlinMod = new ModJarInfo(Path.of("zoomify.jar"), "zoomify", "1.0.0", "Zoomify",
                ModJarInfo.Kind.FABRIC, false, Set.of("kotlin"), Set.of(), Set.of(), List.of());

        assertTrue(hasFinding(ConflictAnalyzer.analyze(List.of(kotlinMod), null),
                Finding.Severity.ERROR, "language adapter"));
    }

    /** Two mods overwriting one class means one of them silently loses its changes. */
    @Test
    void reportsOverwriteConflictsBetweenDifferentMods() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("modA", false, Set.of("net.minecraft.client.LevelRenderer"),
                        Set.of("net.minecraft.client.LevelRenderer")),
                neoForgeMod("modB", Set.of("net.minecraft.client.LevelRenderer"),
                        Set.of("net.minecraft.client.LevelRenderer"))), null);

        assertTrue(hasFinding(findings, Finding.Severity.WARNING, "Conflicting @Overwrite"));
    }

    /**
     * Two copies of one mod overwrite everything in common by definition. Reporting that as an
     * overwrite conflict on top of the duplicate error is pure noise.
     */
    @Test
    void doesNotReportOverwriteConflictForTwoCopiesOfTheSameMod() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("sodium", false, Set.of("net.minecraft.client.LevelRenderer"),
                        Set.of("net.minecraft.client.LevelRenderer")),
                neoForgeMod("sodium", Set.of("net.minecraft.client.LevelRenderer"),
                        Set.of("net.minecraft.client.LevelRenderer"))), null);

        assertTrue(hasFinding(findings, Finding.Severity.ERROR, "installed 2 times"));
        assertFalse(hasFinding(findings, Finding.Severity.WARNING, "Conflicting @Overwrite"));
    }

    /** Groups by mod pair: one pair fighting over 20 classes is one finding, not twenty. */
    @Test
    void groupsOverwriteConflictsByModPair() {
        Set<String> shared = Set.of("net.minecraft.A", "net.minecraft.B", "net.minecraft.C");
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("modA", false, shared, shared),
                neoForgeMod("modB", shared, shared)), null);

        long overwriteFindings = findings.stream()
                .filter(f -> f.title().contains("Conflicting @Overwrite")).count();
        assertEquals(1, overwriteFindings);
    }

    /**
     * The situation this project creates: a Fabric mod and a NeoForge mod touching one class.
     * Nobody upstream tests that combination, so it is surfaced even though it is often fine.
     */
    @Test
    void reportsCrossLoaderMixinOverlap() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("ferritecore", false, Set.of("net.minecraft.world.PalettedContainer"), Set.of()),
                neoForgeMod("sodium", Set.of("net.minecraft.world.PalettedContainer"), Set.of())), null);

        assertTrue(hasFinding(findings, Finding.Severity.WARNING, "Cross-loader overlap"));
    }

    @Test
    void doesNotReportOverlapBetweenTwoModsOnTheSameLoader() {
        // Same-ecosystem mods are developed and reported against each other already.
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                fabricMod("modA", false, Set.of("net.minecraft.Shared"), Set.of()),
                fabricMod("modB", false, Set.of("net.minecraft.Shared"), Set.of())), null);

        assertFalse(hasFinding(findings, Finding.Severity.WARNING, "Cross-loader overlap"));
    }

    @Test
    void alwaysSummarizesFolderContents() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(
                mod("a.jar", "a", ModJarInfo.Kind.FABRIC),
                mod("b.jar", "b", ModJarInfo.Kind.NEOFORGE),
                mod("c.jar", "c", ModJarInfo.Kind.MULTI_LOADER)), null);

        Finding summary = findings.stream()
                .filter(f -> f.title().equals("Folder contents")).findFirst().orElseThrow();
        assertTrue(summary.detail().contains("1 Fabric"));
        assertTrue(summary.detail().contains("1 NeoForge"));
        assertTrue(summary.detail().contains("1 multi-loader"));
    }

    @Test
    void notesThatMultiLoaderJarsBypassTheBridge() {
        List<Finding> findings = ConflictAnalyzer.analyze(
                List.of(mod("arch.jar", "architectury", ModJarInfo.Kind.MULTI_LOADER)), null);

        assertTrue(hasFinding(findings, Finding.Severity.INFO, "Multi-loader jars run natively"));
    }

    @Test
    void handlesAnEmptyFolder() {
        List<Finding> findings = ConflictAnalyzer.analyze(List.of(), null);
        assertFalse(findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR));
    }

    /** Jars with no mod metadata are counted, not silently dropped. */
    @Test
    void countsJarsWithoutModMetadata() {
        ModJarInfo library = new ModJarInfo(Path.of("some-library.jar"), null, null, "some-library.jar",
                ModJarInfo.Kind.UNKNOWN, false, Set.of(), Set.of(), Set.of(), List.of());

        Finding summary = ConflictAnalyzer.analyze(List.of(library), null).stream()
                .filter(f -> f.title().equals("Folder contents")).findFirst().orElseThrow();
        assertTrue(summary.detail().contains("1 without mod metadata"));
    }
}
