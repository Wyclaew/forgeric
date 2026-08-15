package dev.forgeric.loader.metadata;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import dev.forgeric.loader.ForgericConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The converted config is consumed by NeoForge's own ModFileInfo/ModInfo, so it has to match
 * the shape those classes read out of a neoforge.mods.toml. These tests assert against the exact
 * keys NeoForge looks up.
 */
class FabricMetadataConverterTest {

    private static CommentedConfig convert(String json) throws IOException {
        return FabricMetadataConverter.toNeoForgeConfig(FabricModJson.read(new StringReader(json)));
    }

    @SuppressWarnings("unchecked")
    private static List<UnmodifiableConfig> configList(Config config, List<String> path) {
        Optional<Object> value = config.getOptional(path);
        return value.map(o -> List.copyOf((Collection<UnmodifiableConfig>) o)).orElse(List.of());
    }

    @Test
    void routesTheModToTheForgericLanguageLoader() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0"}
                """);
        assertEquals(ForgericConstants.LANGUAGE_LOADER, config.get("modLoader"));
        assertEquals("[1,)", config.get("loaderVersion"));
    }

    /** NeoForge throws InvalidModFileException on a blank license; Fabric makes it optional. */
    @Test
    void alwaysProducesANonBlankLicense() throws IOException {
        String license = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0"}
                """).get("license");
        assertFalse(license == null || license.isBlank());
    }

    @Test
    void keepsADeclaredLicense() throws IOException {
        assertEquals("MIT", convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0", "license": "MIT"}
                """).get("license"));
    }

    @Test
    void writesTheModEntryNeoForgeExpects() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.2.3",
                 "name": "Test Mod", "description": "A mod"}
                """);
        List<UnmodifiableConfig> mods = configList(config, List.of("mods"));
        assertEquals(1, mods.size());

        UnmodifiableConfig mod = mods.getFirst();
        assertEquals("testmod", mod.get("modId"));
        assertEquals("1.2.3", mod.get("version"));
        assertEquals("Test Mod", mod.get("displayName"));
        assertEquals("A mod", mod.get("description"));
    }

    @Test
    void sanitizesTheModIdForNeoForge() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "my-cool-mod", "version": "1.0.0"}
                """);
        assertEquals("my_cool_mod", configList(config, List.of("mods")).getFirst().get("modId"));
    }

    /** NeoForge requires versions to match ^\\d+.* — a leading letter would be rejected. */
    @Test
    void normalizesVersionsThatDoNotStartWithADigit() throws IOException {
        assertEquals("1.0.0", configList(convert("""
                {"schemaVersion": 1, "id": "m", "version": "v1.0.0"}
                """), List.of("mods")).getFirst().get("version"));

        String weird = configList(convert("""
                {"schemaVersion": 1, "id": "m", "version": "alpha"}
                """), List.of("mods")).getFirst().get("version");
        assertTrue(Character.isDigit(weird.charAt(0)), "version must start with a digit, was: " + weird);
    }

    @Test
    void writesDependenciesUnderTheSanitizedModId() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "my-mod", "version": "1.0.0",
                 "depends": {"sodium": ">=0.5.0"}}
                """);
        List<UnmodifiableConfig> dependencies = configList(config, List.of("dependencies", "my_mod"));
        assertEquals(1, dependencies.size());

        UnmodifiableConfig dependency = dependencies.getFirst();
        assertEquals("sodium", dependency.get("modId"));
        assertEquals("required", dependency.get("type"));
        assertEquals("[0.5.0,)", dependency.get("versionRange"));
        assertEquals("NONE", dependency.get("ordering"));
        assertEquals("BOTH", dependency.get("side"));
    }

    /**
     * Loader and game dependencies describe the environment, not other mods. Translating them
     * would make every Fabric mod fail with "fabricloader not found".
     */
    @Test
    void dropsEnvironmentDependencies() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                 "depends": {"fabricloader": ">=0.15.0", "minecraft": "~1.21", "java": ">=21",
                             "sodium": "*"}}
                """);
        List<UnmodifiableConfig> dependencies = configList(config, List.of("dependencies", "testmod"));
        assertEquals(1, dependencies.size());
        assertEquals("sodium", dependencies.getFirst().get("modId"));
    }

    @Test
    void mapsDependencyKindsToNeoForgeTypes() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                 "depends": {"alpha": "*"}, "recommends": {"bravo": "*"},
                 "breaks": {"charlie": "*"}, "conflicts": {"delta": "*"}}
                """);
        List<UnmodifiableConfig> dependencies = configList(config, List.of("dependencies", "testmod"));
        assertEquals(4, dependencies.size());

        assertEquals("required", find(dependencies, "alpha").get("type"));
        assertEquals("optional", find(dependencies, "bravo").get("type"));
        assertEquals("incompatible", find(dependencies, "charlie").get("type"));
        assertEquals("discouraged", find(dependencies, "delta").get("type"));
    }

    @Test
    void sanitizesDependencyIdsToo() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                 "depends": {"fabric-api-base": "*"}}
                """);
        assertEquals("fabric_api_base",
                configList(config, List.of("dependencies", "testmod")).getFirst().get("modId"));
    }

    /** Handing mixin configs to NeoForge is what removes the need for a separate mixin bridge. */
    @Test
    void passesMixinConfigsThroughForNeoForgeToLoad() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                 "mixins": ["testmod.mixins.json", {"config": "testmod.client.mixins.json"}]}
                """);
        List<UnmodifiableConfig> mixins = configList(config, List.of("mixins"));
        assertEquals(2, mixins.size());
        assertEquals("testmod.mixins.json", mixins.get(0).get("config"));
        assertEquals("testmod.client.mixins.json", mixins.get(1).get("config"));
    }

    @Test
    void omitsEmptySectionsEntirely() throws IOException {
        CommentedConfig config = convert("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0"}
                """);
        assertTrue(configList(config, List.of("mixins")).isEmpty());
        assertTrue(configList(config, List.of("dependencies", "testmod")).isEmpty());
    }

    private static UnmodifiableConfig find(List<UnmodifiableConfig> dependencies, String modId) {
        return dependencies.stream()
                .filter(d -> modId.equals(d.get("modId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no dependency on " + modId));
    }
}
