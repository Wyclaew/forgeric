package dev.forgeric.loader.metadata;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps Fabric mod ids onto ids NeoForge will accept, and remembers the mapping both ways.
 *
 * <p>The two ecosystems disagree on what a legal mod id is. NeoForge enforces
 * {@code ^(?=.{2,64}$)[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$} — no hyphens. Fabric allows them,
 * and uses them heavily: {@code fabric-api-base}, {@code fabric-networking-api-v1}. Feeding
 * those to NeoForge unchanged makes it reject the mod outright.
 *
 * <p>So NeoForge sees {@code fabric_api_base} while Fabric-facing APIs keep seeing
 * {@code fabric-api-base}. Both directions are recorded here so that a mod calling
 * {@code FabricLoader.isModLoaded("fabric-api-base")} still gets a truthful answer.
 *
 * <p>Collisions are real — {@code fabric-api} and {@code fabric_api} both sanitize to
 * {@code fabric_api} — so a suffix is appended when a translated id is already taken by a
 * different original.
 */
public final class ForgericIds {
    private ForgericIds() {}

    private static final Pattern VALID_NEOFORGE_ID =
            Pattern.compile("^(?=.{2,64}$)[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

    private static final Map<String, String> FABRIC_TO_NEOFORGE = new ConcurrentHashMap<>();
    private static final Map<String, String> NEOFORGE_TO_FABRIC = new ConcurrentHashMap<>();

    /**
     * {@return the NeoForge-legal id for a Fabric mod id}
     * Stable for the lifetime of the process: repeated calls return the same value.
     */
    public static String toNeoForge(String fabricId) {
        String existing = FABRIC_TO_NEOFORGE.get(fabricId);
        if (existing != null) return existing;

        synchronized (ForgericIds.class) {
            existing = FABRIC_TO_NEOFORGE.get(fabricId);
            if (existing != null) return existing;

            String candidate = sanitize(fabricId);
            // Resolve collisions against a *different* original id.
            if (NEOFORGE_TO_FABRIC.containsKey(candidate) && !fabricId.equals(NEOFORGE_TO_FABRIC.get(candidate))) {
                int suffix = 2;
                String base = candidate;
                while (NEOFORGE_TO_FABRIC.containsKey(candidate)) {
                    candidate = truncate(base, 62) + "_" + suffix++;
                }
            }

            FABRIC_TO_NEOFORGE.put(fabricId, candidate);
            NEOFORGE_TO_FABRIC.put(candidate, fabricId);
            return candidate;
        }
    }

    /**
     * {@return the original Fabric mod id for a translated id}
     * Falls back to the input when the id was never translated (e.g. a genuine NeoForge mod).
     */
    public static String toFabric(String neoForgeId) {
        return NEOFORGE_TO_FABRIC.getOrDefault(neoForgeId, neoForgeId);
    }

    /** {@return true if this id was produced by translating a Fabric mod id} */
    public static boolean isTranslated(String neoForgeId) {
        return NEOFORGE_TO_FABRIC.containsKey(neoForgeId);
    }

    /** {@return an unmodifiable view of every translation made so far, keyed by Fabric id} */
    public static Map<String, String> translations() {
        return Map.copyOf(FABRIC_TO_NEOFORGE);
    }

    private static String sanitize(String fabricId) {
        String lower = fabricId.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == '.' && i > 0 && i < lower.length() - 1) {
                sb.append(c); // dots are legal as package-style separators, but not at the edges
            } else {
                sb.append('_'); // hyphens and everything else
            }
        }

        // Must start with a letter.
        if (sb.isEmpty() || sb.charAt(0) < 'a' || sb.charAt(0) > 'z') {
            sb.insert(0, 'm');
        }
        // A segment may not start with a digit either: "a.1b" is invalid.
        for (int i = 1; i < sb.length(); i++) {
            if (sb.charAt(i - 1) == '.' && !(sb.charAt(i) >= 'a' && sb.charAt(i) <= 'z')) {
                sb.insert(i, 'm');
            }
        }
        while (sb.length() < 2) {
            sb.append('_');
        }

        String result = truncate(sb.toString(), 64);
        // Defensive: if anything above still produced an illegal id, fall back to a hashed name
        // rather than letting NeoForge reject the mod with a confusing error.
        if (!VALID_NEOFORGE_ID.matcher(result).matches()) {
            result = "forgeric_mod_" + Integer.toHexString(fabricId.hashCode()).replace("-", "");
        }
        return result;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
