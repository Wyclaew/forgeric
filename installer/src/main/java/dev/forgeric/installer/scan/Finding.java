package dev.forgeric.installer.scan;

/**
 * One problem found in a mods folder.
 *
 * <p>Severity drives what the user should do, so it is kept deliberately coarse:
 * an ERROR means the pack will not work as installed, a WARNING means it may misbehave in ways
 * that are hard to diagnose later, and INFO is context worth knowing but needs no action.
 */
public record Finding(Severity severity, String title, String detail, String suggestion) {

    public enum Severity {
        /** Will not work. Something must be removed or added. */
        ERROR,
        /** Likely to break at runtime, or silently degrade. */
        WARNING,
        /** Worth knowing, no action needed. */
        INFO
    }

    public static Finding error(String title, String detail, String suggestion) {
        return new Finding(Severity.ERROR, title, detail, suggestion);
    }

    public static Finding warning(String title, String detail, String suggestion) {
        return new Finding(Severity.WARNING, title, detail, suggestion);
    }

    public static Finding info(String title, String detail) {
        return new Finding(Severity.INFO, title, detail, null);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (severity) {
            case ERROR -> "ERROR   ";
            case WARNING -> "WARNING ";
            case INFO -> "INFO    ";
        });
        sb.append(title).append('\n');
        sb.append("        ").append(detail);
        if (suggestion != null) {
            sb.append('\n').append("        -> ").append(suggestion);
        }
        return sb.toString();
    }
}
