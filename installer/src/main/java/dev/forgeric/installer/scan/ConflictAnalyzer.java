package dev.forgeric.installer.scan;

import dev.forgeric.installer.profile.VersionProfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a scanned mods folder into a list of concrete problems.
 *
 * <p>Most mods were never built to sit next to the other loader's ecosystem, so a folder that
 * looks fine can fail in ways that are painful to diagnose from a crash log — two copies of the
 * same mod, a mixin fighting another mixin over the same method, a Fabric mod quietly missing
 * an API that does not exist here. Each check below corresponds to a failure mode that is
 * cheap to detect now and expensive to debug later.
 */
public final class ConflictAnalyzer {
    private ConflictAnalyzer() {}

    public static List<Finding> analyze(List<ModJarInfo> mods, VersionProfile profile) {
        List<Finding> findings = new ArrayList<>();

        findDuplicateModIds(mods, findings);
        findMissingFabricApi(mods, profile, findings);
        findUnsupportedLanguageAdapters(mods, findings);
        findOverwriteCollisions(mods, findings);
        findCrossLoaderMixinOverlap(mods, findings);
        findSharedMixinTargets(mods, findings);
        reportComposition(mods, findings);

        return findings;
    }

    /**
     * The single most common way to break a mixed pack: dropping in both the Fabric build and
     * the NeoForge build of a mod that ships for both. They declare the same mod id, apply the
     * same mixins twice, and register the same content twice.
     */
    private static void findDuplicateModIds(List<ModJarInfo> mods, List<Finding> findings) {
        Map<String, List<ModJarInfo>> byId = new LinkedHashMap<>();
        for (ModJarInfo mod : mods) {
            if (mod.modId() != null) {
                byId.computeIfAbsent(mod.modId(), k -> new ArrayList<>()).add(mod);
            }
        }

        for (var entry : byId.entrySet()) {
            List<ModJarInfo> duplicates = entry.getValue();
            if (duplicates.size() < 2) continue;

            String files = duplicates.stream()
                    .map(m -> m.path().getFileName() + " (" + m.kind() + ")")
                    .collect(Collectors.joining(", "));

            boolean crossLoader = duplicates.stream().map(ModJarInfo::kind).distinct().count() > 1;
            String suggestion = crossLoader
                    ? "Keep the NeoForge build and delete the Fabric one. A mod that ships natively for "
                      + "NeoForge always runs better there than through the bridge."
                    : "Delete all but one copy.";

            findings.add(Finding.error(
                    "Mod '" + entry.getKey() + "' is installed " + duplicates.size() + " times",
                    files, suggestion));
        }
    }

    /**
     * Fabric mods overwhelmingly depend on fabric-api, and the bridge for it does not exist yet.
     * Catching this here turns a confusing NoClassDefFoundError mid-launch into a plain list.
     */
    private static void findMissingFabricApi(List<ModJarInfo> mods, VersionProfile profile,
                                             List<Finding> findings) {
        if (profile != null && profile.fabricApiSupported()) {
            return;
        }

        List<ModJarInfo> needsApi = mods.stream()
                .filter(ModJarInfo::isFabricSide)
                .filter(ModJarInfo::requiresFabricApi)
                .toList();
        if (needsApi.isEmpty()) return;

        String names = needsApi.stream().map(ModJarInfo::label).collect(Collectors.joining(", "));
        findings.add(Finding.error(
                needsApi.size() + " Fabric mod(s) require the Fabric API",
                names,
                "These cannot load yet: Forgeric has no Fabric API bridge. Remove them for now, "
                + "or use their NeoForge build if one exists."));
    }

    /** Kotlin and Scala entrypoints go through adapter mods that Forgeric cannot run yet. */
    private static void findUnsupportedLanguageAdapters(List<ModJarInfo> mods, List<Finding> findings) {
        for (ModJarInfo mod : mods) {
            if (!mod.isFabricSide() || mod.languageAdapters().isEmpty()) continue;
            findings.add(Finding.error(
                    mod.label() + " needs a language adapter Forgeric does not support",
                    "Adapters: " + String.join(", ", mod.languageAdapters()),
                    "Only plain Java entrypoints run today. Remove this mod, or use its NeoForge build."));
        }
    }

    /**
     * Two mixins that {@code @Overwrite} the same class are a genuine conflict: an overwrite
     * replaces a method body outright, so whichever applies second wins and the other mod's
     * behaviour silently disappears.
     */
    private static void findOverwriteCollisions(List<ModJarInfo> mods, List<Finding> findings) {
        Map<String, List<ModJarInfo>> byTarget = new LinkedHashMap<>();
        for (ModJarInfo mod : mods) {
            for (String target : mod.overwriteTargets()) {
                byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(mod);
            }
        }

        // Grouped by the pair of mods rather than by class: a pair that fights over one class
        // usually fights over a dozen, and a dozen near-identical warnings buries everything else.
        Map<String, Set<String>> byPair = new LinkedHashMap<>();
        for (var entry : byTarget.entrySet()) {
            List<ModJarInfo> owners = entry.getValue();
            if (owners.size() < 2) continue;

            // Two copies of one mod are already reported as a duplicate install; repeating the
            // overwrite overlap for every class they share adds noise and no information.
            boolean sameModTwice = owners.stream()
                    .map(ModJarInfo::modId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count() <= 1;
            if (sameModTwice) continue;

            String pair = owners.stream().map(ModJarInfo::label).distinct().sorted()
                    .collect(Collectors.joining(" + "));
            byPair.computeIfAbsent(pair, k -> new LinkedHashSet<>()).add(shortName(entry.getKey()));
        }

        for (var entry : byPair.entrySet()) {
            Set<String> classes = entry.getValue();
            String sample = classes.stream().limit(5).collect(Collectors.joining(", "));
            if (classes.size() > 5) sample += ", +" + (classes.size() - 5) + " more";

            findings.add(Finding.warning(
                    "Conflicting @Overwrite: " + entry.getKey(),
                    "Both replace methods in " + classes.size() + " shared class(es): " + sample,
                    "An @Overwrite replaces a method outright, so whichever applies second wins "
                    + "and the other mod's change disappears. Keep only one of these."));
        }
    }

    /**
     * A Fabric mod and a NeoForge mod patching the same class is the situation this whole
     * project creates, and the one nobody upstream has tested.
     *
     * <p>Two mods from the same ecosystem are at least developed against each other and tend to
     * be reported by their users when they clash. A cross-loader pair has never run together
     * anywhere, so overlap is worth surfacing even though it is often harmless.
     */
    private static void findCrossLoaderMixinOverlap(List<ModJarInfo> mods, List<Finding> findings) {
        List<ModJarInfo> fabricMods = mods.stream().filter(ModJarInfo::isFabricSide).toList();
        List<ModJarInfo> neoForgeMods = mods.stream()
                .filter(m -> m.kind() == ModJarInfo.Kind.NEOFORGE || m.kind() == ModJarInfo.Kind.MULTI_LOADER)
                .toList();
        if (fabricMods.isEmpty() || neoForgeMods.isEmpty()) return;

        Map<String, Set<String>> overlaps = new LinkedHashMap<>();
        for (ModJarInfo fabricMod : fabricMods) {
            for (ModJarInfo neoForgeMod : neoForgeMods) {
                // Same mod on both sides is already reported as a duplicate; don't repeat it.
                if (fabricMod.modId() != null && fabricMod.modId().equals(neoForgeMod.modId())) continue;

                Set<String> shared = new LinkedHashSet<>(fabricMod.mixinTargets());
                shared.retainAll(neoForgeMod.mixinTargets());
                if (shared.isEmpty()) continue;

                String pair = fabricMod.label() + " (Fabric) + " + neoForgeMod.label() + " (NeoForge)";
                overlaps.computeIfAbsent(pair, k -> new LinkedHashSet<>())
                        .addAll(shared.stream().map(ConflictAnalyzer::shortName).toList());
            }
        }

        for (var entry : overlaps.entrySet()) {
            Set<String> classes = entry.getValue();
            String sample = classes.stream().limit(4).collect(Collectors.joining(", "));
            if (classes.size() > 4) sample += ", +" + (classes.size() - 4) + " more";

            findings.add(Finding.warning(
                    "Cross-loader overlap: " + entry.getKey(),
                    "Both patch " + classes.size() + " of the same class(es): " + sample,
                    "Usually fine, but this pair has never been tested together. If the game "
                    + "misbehaves, remove one of them first."));
        }
    }

    /**
     * Plain mixins into a shared class usually coexist fine, so this is reported only when a
     * class is unusually contested, and only as information.
     */
    private static void findSharedMixinTargets(List<ModJarInfo> mods, List<Finding> findings) {
        Map<String, Set<String>> byTarget = new LinkedHashMap<>();
        for (ModJarInfo mod : mods) {
            for (String target : mod.mixinTargets()) {
                byTarget.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(mod.label());
            }
        }

        List<String> contested = byTarget.entrySet().stream()
                .filter(e -> e.getValue().size() >= 3)
                .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                .limit(5)
                .map(e -> shortName(e.getKey()) + " (" + e.getValue().size() + " mods)")
                .toList();

        if (!contested.isEmpty()) {
            findings.add(Finding.info(
                    "Heavily patched classes",
                    String.join(", ", contested)
                            + ". Normal for mixed packs, but the first place to look if the game misbehaves."));
        }
    }

    /** A plain summary of what the folder contains, which is often the answer by itself. */
    private static void reportComposition(List<ModJarInfo> mods, List<Finding> findings) {
        long fabric = mods.stream().filter(m -> m.kind() == ModJarInfo.Kind.FABRIC).count();
        long neoforge = mods.stream().filter(m -> m.kind() == ModJarInfo.Kind.NEOFORGE).count();
        long multi = mods.stream().filter(m -> m.kind() == ModJarInfo.Kind.MULTI_LOADER).count();
        long unknown = mods.stream().filter(m -> m.kind() == ModJarInfo.Kind.UNKNOWN).count();

        findings.add(Finding.info("Folder contents",
                mods.size() + " jar(s): " + fabric + " Fabric, " + neoforge + " NeoForge, "
                        + multi + " multi-loader, " + unknown + " without mod metadata"));

        if (multi > 0) {
            findings.add(Finding.info("Multi-loader jars run natively",
                    multi + " jar(s) ship both loaders' metadata, so NeoForge loads them directly "
                            + "and the bridge stays out of the way. That is the fastest path."));
        }

        List<String> nested = mods.stream()
                .filter(m -> !m.nestedJars().isEmpty())
                // Two mods can share a display name (the same mod for both loaders), so the
                // jar name is what actually identifies the entry here.
                .map(m -> m.path().getFileName() + " (" + m.nestedJars().size() + ")")
                .toList();
        if (!nested.isEmpty()) {
            findings.add(Finding.info("Mods bundling libraries",
                    String.join(", ", nested) + ". Bundled copies of the same library can collide "
                            + "with a library another mod ships."));
        }
    }

    private static String shortName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }
}
