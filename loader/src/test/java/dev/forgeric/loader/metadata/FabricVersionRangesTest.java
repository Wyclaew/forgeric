package dev.forgeric.loader.metadata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fabric's semver predicates vs Maven's range syntax. Getting this wrong makes mods either
 * refuse to load or load against versions they declared incompatible, so each supported
 * predicate form is pinned down here.
 */
class FabricVersionRangesTest {

    @Test
    void wildcardBecomesUnbounded() {
        assertEquals(FabricVersionRanges.UNBOUNDED, FabricVersionRanges.toMavenRange(List.of("*")));
    }

    @Test
    void greaterOrEqualBecomesInclusiveLowerBound() {
        assertEquals("[1.0.0,)", FabricVersionRanges.toMavenRange(List.of(">=1.0.0")));
    }

    @Test
    void strictlyGreaterBecomesExclusiveLowerBound() {
        assertEquals("(1.0.0,)", FabricVersionRanges.toMavenRange(List.of(">1.0.0")));
    }

    @Test
    void lessThanBecomesExclusiveUpperBound() {
        assertEquals("(,2.0.0)", FabricVersionRanges.toMavenRange(List.of("<2.0.0")));
    }

    @Test
    void lessOrEqualBecomesInclusiveUpperBound() {
        assertEquals("(,2.0.0]", FabricVersionRanges.toMavenRange(List.of("<=2.0.0")));
    }

    @Test
    void caretPinsMajorVersion() {
        // ^1.2.3 allows 1.x but not 2.0
        assertEquals("[1.2.3,2)", FabricVersionRanges.toMavenRange(List.of("^1.2.3")));
    }

    @Test
    void tildePinsMinorVersion() {
        // ~1.2.3 allows 1.2.x but not 1.3
        assertEquals("[1.2.3,1.3)", FabricVersionRanges.toMavenRange(List.of("~1.2.3")));
    }

    @Test
    void exactVersionBecomesPinnedRange() {
        assertEquals("[1.2.3]", FabricVersionRanges.toMavenRange(List.of("1.2.3")));
        assertEquals("[1.2.3]", FabricVersionRanges.toMavenRange(List.of("=1.2.3")));
    }

    @Test
    void wildcardSegmentBecomesBoundedRange() {
        assertEquals("[1.2,1.3)", FabricVersionRanges.toMavenRange(List.of("1.2.x")));
    }

    /**
     * The dialects disagree here: Fabric ANDs a list of predicates, Maven ORs a comma-separated
     * range list. Predicates must therefore be intersected into a single range.
     */
    @Test
    void multiplePredicatesAreIntersectedNotUnioned() {
        assertEquals("[1.2,2.0)", FabricVersionRanges.toMavenRange(List.of(">=1.2", "<2.0")));
    }

    @Test
    void spaceSeparatedPredicatesInOneStringAreAlsoIntersected() {
        assertEquals("[1.2,2.0)", FabricVersionRanges.toMavenRange(List.of(">=1.2 <2.0")));
    }

    @Test
    void tightestBoundWins() {
        assertEquals("[1.5,)", FabricVersionRanges.toMavenRange(List.of(">=1.0", ">=1.5")));
    }

    @Test
    void buildMetadataIsStrippedBecauseMavenCannotCompareIt() {
        assertEquals("[0.19.3,)", FabricVersionRanges.toMavenRange(List.of(">=0.19.3+build.42")));
    }

    /**
     * An unparseable predicate must not fail the mod: too-permissive means the game still starts
     * and reports a real problem later, whereas rejecting here blocks a mod that may be fine.
     */
    @Test
    void unknownPredicateDegradesToUnbounded() {
        assertEquals(FabricVersionRanges.UNBOUNDED, FabricVersionRanges.toMavenRange(List.of("not-a-version")));
    }

    @Test
    void emptyPredicateListIsUnbounded() {
        assertEquals(FabricVersionRanges.UNBOUNDED, FabricVersionRanges.toMavenRange(List.of()));
    }

    /** Maven treats an empty spec as "matches nothing", so the unbounded marker must not be blank. */
    @Test
    void unboundedIsNotAnEmptyString() {
        assertTrue(FabricVersionRanges.UNBOUNDED.length() > 0);
    }
}
