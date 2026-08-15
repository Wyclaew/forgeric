package dev.forgeric.loader.metadata;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NeoForge enforces a stricter mod id grammar than Fabric. Every translated id must satisfy it,
 * and the translation has to be reversible so Fabric-facing lookups still work.
 */
class ForgericIdsTest {

    /** Copied from NeoForge's ModInfo so the tests fail if our output would be rejected there. */
    private static final Pattern VALID_NEOFORGE_ID =
            Pattern.compile("^(?=.{2,64}$)[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static void assertLegal(String id) {
        assertTrue(VALID_NEOFORGE_ID.matcher(id).matches(), "NeoForge would reject the id: " + id);
    }

    @Test
    void hyphensBecomeUnderscores() {
        String translated = ForgericIds.toNeoForge("fabric-api-base");
        assertEquals("fabric_api_base", translated);
        assertLegal(translated);
    }

    @Test
    void alreadyLegalIdsAreLeftAlone() {
        assertEquals("sodium", ForgericIds.toNeoForge("sodium"));
    }

    @Test
    void translationIsReversible() {
        String translated = ForgericIds.toNeoForge("fabric-networking-api-v1");
        assertEquals("fabric-networking-api-v1", ForgericIds.toFabric(translated));
    }

    @Test
    void translationIsStableAcrossCalls() {
        assertEquals(ForgericIds.toNeoForge("some-mod"), ForgericIds.toNeoForge("some-mod"));
    }

    @Test
    void unknownIdsPassThroughUntouchedOnReverseLookup() {
        // A genuine NeoForge mod id was never translated and must survive a reverse lookup.
        assertEquals("jei", ForgericIds.toFabric("jei"));
    }

    /**
     * Both "x-y" and "x_y" sanitize to "x_y". They are different mods and must not collapse
     * into one id, or one would silently shadow the other.
     */
    @Test
    void collidingIdsGetDistinctTranslations() {
        String first = ForgericIds.toNeoForge("collide-test");
        String second = ForgericIds.toNeoForge("collide_test");
        assertNotEquals(first, second);
        assertLegal(first);
        assertLegal(second);
        assertEquals("collide-test", ForgericIds.toFabric(first));
        assertEquals("collide_test", ForgericIds.toFabric(second));
    }

    @Test
    void uppercaseIsLowered() {
        String translated = ForgericIds.toNeoForge("MyMod");
        assertEquals("mymod", translated);
        assertLegal(translated);
    }

    @Test
    void idsStartingWithADigitGetALetterPrefix() {
        String translated = ForgericIds.toNeoForge("2fast2furious");
        assertLegal(translated);
        assertEquals("2fast2furious", ForgericIds.toFabric(translated));
    }

    @Test
    void singleCharacterIdsArePaddedToTheMinimumLength() {
        String translated = ForgericIds.toNeoForge("q");
        assertLegal(translated);
    }

    @Test
    void overlongIdsAreTruncatedToTheMaximumLength() {
        String translated = ForgericIds.toNeoForge("a".repeat(200));
        assertLegal(translated);
        assertTrue(translated.length() <= 64);
    }

    @Test
    void exoticCharactersStillProduceALegalId() {
        for (String weird : new String[] {"mod!!", "übermod", "mod@1.0", "-leading", "trailing-", "..."}) {
            assertLegal(ForgericIds.toNeoForge(weird));
        }
    }

    @Test
    void dottedIdsKeepTheirPackageStyleSeparators() {
        String translated = ForgericIds.toNeoForge("com.example.mod");
        assertEquals("com.example.mod", translated);
        assertLegal(translated);
    }
}
