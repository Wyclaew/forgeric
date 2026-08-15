package dev.forgeric.installer.scan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.forgeric.installer.profile.VersionProfile;

/** Builds throwaway {@link VersionProfile}s for tests, since its constructor is private by design. */
final class TestProfiles {
    private TestProfiles() {}

    static VersionProfile withFabricApi(boolean supported) {
        JsonObject root = JsonParser.parseString("""
                {
                  "profileVersion": 1,
                  "minecraft": "26.2",
                  "javaMajor": 25,
                  "obfuscated": false,
                  "neoforge": {"version": "26.2.0.59"},
                  "fabric": {"loader": "0.19.3"},
                  "supported": {"status": "alpha", "fabricApi": %s}
                }
                """.formatted(supported)).getAsJsonObject();
        return VersionProfile.parse(root);
    }
}
