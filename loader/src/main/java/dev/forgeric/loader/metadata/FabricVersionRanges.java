package dev.forgeric.loader.metadata;

import org.apache.maven.artifact.versioning.ComparableVersion;

import java.util.List;

/**
 * Translates Fabric's semver-style version predicates into Maven version ranges,
 * which is the only range dialect NeoForge's metadata model understands.
 *
 * <p>The two dialects differ in a way that matters: Fabric treats a list of predicates as
 * a conjunction ({@code [">=1.2", "<2.0"]} means both must hold), while Maven treats a
 * comma-separated range list as a disjunction. So predicates are parsed into bounds and
 * intersected here, then emitted as one Maven range.
 *
 * <p>Anything not understood degrades to an unbounded range rather than failing the mod.
 * A dependency that is too permissive lets the game start and surfaces a real error later;
 * a parse failure would block a mod that might have been fine.
 */
public final class FabricVersionRanges {
    private FabricVersionRanges() {}

    /** Maven's "accepts anything" range. The space is required — an empty spec matches nothing. */
    public static final String UNBOUNDED = " ";

    public static String toMavenRange(List<String> fabricPredicates) {
        Bounds bounds = new Bounds();
        for (String predicate : fabricPredicates) {
            for (String part : predicate.trim().split("\\s+")) {
                if (part.isEmpty()) continue;
                if (!bounds.apply(part)) {
                    return UNBOUNDED; // unparseable predicate — do not guess
                }
            }
        }
        return bounds.toMavenRange();
    }

    /** Accumulates the intersection of all predicates seen so far. */
    private static final class Bounds {
        private ComparableVersion lower;
        private boolean lowerInclusive = true;
        private ComparableVersion upper;
        private boolean upperInclusive = false;
        private ComparableVersion exact;

        /** @return false if the predicate could not be understood */
        boolean apply(String predicate) {
            if (predicate.equals("*") || predicate.equalsIgnoreCase("any")) {
                return true;
            }
            if (predicate.startsWith(">=")) {
                String base = predicate.substring(2);
                if (!isVersionLike(base)) return false;
                raiseLower(version(base), true);
            } else if (predicate.startsWith("<=")) {
                String base = predicate.substring(2);
                if (!isVersionLike(base)) return false;
                lowerUpper(version(base), true);
            } else if (predicate.startsWith(">")) {
                String base = predicate.substring(1);
                if (!isVersionLike(base)) return false;
                raiseLower(version(base), false);
            } else if (predicate.startsWith("<")) {
                String base = predicate.substring(1);
                if (!isVersionLike(base)) return false;
                lowerUpper(version(base), false);
            } else if (predicate.startsWith("^")) {
                // ^1.2.3 -> >=1.2.3 <2.0.0
                String base = predicate.substring(1);
                if (!isVersionLike(base)) return false;
                raiseLower(version(base), true);
                lowerUpper(version(bumpSegment(base, 0)), false);
            } else if (predicate.startsWith("~")) {
                // ~1.2.3 -> >=1.2.3 <1.3.0
                String base = predicate.substring(1);
                if (!isVersionLike(base)) return false;
                raiseLower(version(base), true);
                lowerUpper(version(bumpSegment(base, 1)), false);
            } else if (predicate.contains("x") || predicate.contains("X")) {
                return applyWildcard(predicate);
            } else {
                String base = predicate.startsWith("=") ? predicate.substring(1) : predicate;
                if (!isVersionLike(base)) return false;
                exact = version(base);
            }
            return true;
        }

        /**
         * Maven cannot parse a range built from a non-version string, and NeoForge would reject
         * the mod outright. Anything not starting with a digit is treated as unparseable so the
         * caller degrades to an unbounded range instead of emitting garbage.
         */
        private static boolean isVersionLike(String value) {
            String trimmed = value.trim();
            return !trimmed.isEmpty() && Character.isDigit(trimmed.charAt(0));
        }

        /** {@code 1.2.x} → {@code >=1.2 <1.3} */
        private boolean applyWildcard(String predicate) {
            String[] segments = predicate.split("\\.");
            StringBuilder prefix = new StringBuilder();
            int fixed = 0;
            for (String segment : segments) {
                if (segment.equalsIgnoreCase("x") || segment.equals("*")) break;
                if (fixed > 0) prefix.append('.');
                prefix.append(segment);
                fixed++;
            }
            if (fixed == 0) return true; // "x" alone means any version
            String base = prefix.toString();
            raiseLower(version(base), true);
            lowerUpper(version(bumpSegment(base, fixed - 1)), false);
            return true;
        }

        private void raiseLower(ComparableVersion candidate, boolean inclusive) {
            if (lower == null || candidate.compareTo(lower) > 0) {
                lower = candidate;
                lowerInclusive = inclusive;
            } else if (candidate.compareTo(lower) == 0 && !inclusive) {
                lowerInclusive = false;
            }
        }

        private void lowerUpper(ComparableVersion candidate, boolean inclusive) {
            if (upper == null || candidate.compareTo(upper) < 0) {
                upper = candidate;
                upperInclusive = inclusive;
            } else if (candidate.compareTo(upper) == 0 && !inclusive) {
                upperInclusive = false;
            }
        }

        String toMavenRange() {
            if (exact != null) {
                return "[" + exact + "]";
            }
            if (lower == null && upper == null) {
                return UNBOUNDED;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(lowerInclusive && lower != null ? '[' : '(');
            if (lower != null) sb.append(lower);
            sb.append(',');
            if (upper != null) sb.append(upper);
            sb.append(upper != null && upperInclusive ? ']' : ')');
            return sb.toString();
        }

        private static ComparableVersion version(String raw) {
            return new ComparableVersion(stripBuildMetadata(raw.trim()));
        }
    }

    /**
     * Increments the segment at {@code index} and drops everything after it,
     * producing the exclusive upper bound for {@code ^} / {@code ~} / wildcard ranges.
     */
    private static String bumpSegment(String version, int index) {
        String[] segments = stripBuildMetadata(version).split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= index; i++) {
            if (i > 0) sb.append('.');
            if (i == index) {
                sb.append(parseSegment(i < segments.length ? segments[i] : "0") + 1);
            } else {
                sb.append(i < segments.length ? segments[i] : "0");
            }
        }
        return sb.toString();
    }

    private static int parseSegment(String segment) {
        StringBuilder digits = new StringBuilder();
        for (char c : segment.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Maven cannot compare semver build metadata, and Fabric ranges rarely rely on it. */
    private static String stripBuildMetadata(String version) {
        int plus = version.indexOf('+');
        return plus >= 0 ? version.substring(0, plus) : version;
    }
}
