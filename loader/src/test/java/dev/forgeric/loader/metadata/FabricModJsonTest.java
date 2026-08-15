package dev.forgeric.loader.metadata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fabric.mod.json permits several shapes for the same field (a string or an object, a string or
 * an array). Real mods use all of them, so the reader is exercised against each form.
 */
class FabricModJsonTest {

    private static FabricModJson read(String json) throws IOException {
        return FabricModJson.read(new StringReader(json));
    }

    @Test
    void readsAMinimalMod() throws IOException {
        FabricModJson mod = read("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0"}
                """);
        assertEquals("testmod", mod.id());
        assertEquals("1.0.0", mod.version());
        assertEquals("testmod", mod.name(), "name should fall back to the id");
        assertTrue(mod.entrypoints().isEmpty());
        assertTrue(mod.mixins().isEmpty());
    }

    @Test
    void missingIdIsRejected() {
        assertThrows(IOException.class, () -> read("""
                {"schemaVersion": 1, "version": "1.0.0"}
                """));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IOException.class, () -> read("not json at all"));
    }

    /** Loom leaves "${version}" in place when a jar is built without expansion. */
    @Test
    void unexpandedVersionPlaceholderFallsBackToAValidVersion() throws IOException {
        FabricModJson mod = read("""
                {"schemaVersion": 1, "id": "testmod", "version": "${version}"}
                """);
        assertEquals("0.0.0", mod.version());
    }

    @Test
    void readsBothEntrypointForms() throws IOException {
        FabricModJson mod = read("""
                {
                  "schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                  "entrypoints": {
                    "main": ["com.example.Mod"],
                    "client": [{"adapter": "kotlin", "value": "com.example.ClientMod"}]
                  }
                }
                """);
        List<FabricModJson.Entrypoint> main = mod.entrypoints("main");
        assertEquals(1, main.size());
        assertEquals("com.example.Mod", main.getFirst().value());
        assertEquals("default", main.getFirst().adapter());

        List<FabricModJson.Entrypoint> client = mod.entrypoints("client");
        assertEquals("kotlin", client.getFirst().adapter());
        assertEquals("com.example.ClientMod", client.getFirst().value());
    }

    @Test
    void missingEntrypointKeyReturnsEmptyList() throws IOException {
        FabricModJson mod = read("""
                {"schemaVersion": 1, "id": "testmod", "version": "1.0.0"}
                """);
        assertTrue(mod.entrypoints("server").isEmpty());
    }

    @Test
    void readsBothMixinForms() throws IOException {
        FabricModJson mod = read("""
                {
                  "schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                  "mixins": ["testmod.mixins.json", {"config": "testmod.client.mixins.json", "environment": "client"}]
                }
                """);
        assertEquals(2, mod.mixins().size());
        assertEquals("testmod.mixins.json", mod.mixins().get(0).config());
        assertEquals("*", mod.mixins().get(0).environment());
        assertEquals("client", mod.mixins().get(1).environment());
    }

    @Test
    void normalizesAllFiveDependencyBlocks() throws IOException {
        FabricModJson mod = read("""
                {
                  "schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                  "depends":    {"fabricloader": ">=0.15.0", "sodium": "*"},
                  "recommends": {"modmenu": "*"},
                  "breaks":     {"oldmod": "<1.0.0"},
                  "conflicts":  {"rivalmod": "*"}
                }
                """);
        assertEquals(5, mod.dependencies().size());

        FabricModJson.Dependency loader = mod.dependencies().stream()
                .filter(d -> d.modId().equals("fabricloader")).findFirst().orElseThrow();
        assertEquals(FabricModJson.Dependency.Kind.REQUIRED, loader.kind());
        assertEquals(List.of(">=0.15.0"), loader.versionRanges());

        FabricModJson.Dependency breaks = mod.dependencies().stream()
                .filter(d -> d.modId().equals("oldmod")).findFirst().orElseThrow();
        assertEquals(FabricModJson.Dependency.Kind.INCOMPATIBLE, breaks.kind());
    }

    @Test
    void readsDependencyRangesGivenAsAnArray() throws IOException {
        FabricModJson mod = read("""
                {
                  "schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                  "depends": {"minecraft": [">=1.21", "<2.0"]}
                }
                """);
        assertEquals(List.of(">=1.21", "<2.0"), mod.dependencies().getFirst().versionRanges());
    }

    @Test
    void readsLicenseAsStringOrArray() throws IOException {
        assertEquals("MIT", read("""
                {"schemaVersion": 1, "id": "m", "version": "1.0.0", "license": "MIT"}
                """).license());

        assertEquals("MIT, Apache-2.0", read("""
                {"schemaVersion": 1, "id": "m", "version": "1.0.0", "license": ["MIT", "Apache-2.0"]}
                """).license());
    }

    @Test
    void readsAccessWidenerAndNestedJars() throws IOException {
        FabricModJson mod = read("""
                {
                  "schemaVersion": 1, "id": "testmod", "version": "1.0.0",
                  "accessWidener": "testmod.accesswidener",
                  "jars": [{"file": "META-INF/jars/nested-1.0.0.jar"}]
                }
                """);
        assertEquals("testmod.accesswidener", mod.accessWidener());
        assertEquals(List.of("META-INF/jars/nested-1.0.0.jar"), mod.nestedJars());
    }

    @Test
    void accessWidenerIsNullWhenAbsent() throws IOException {
        assertNull(read("""
                {"schemaVersion": 1, "id": "m", "version": "1.0.0"}
                """).accessWidener());
    }
}
