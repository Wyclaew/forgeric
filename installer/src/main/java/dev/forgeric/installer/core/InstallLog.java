package dev.forgeric.installer.core;

import java.util.function.Consumer;

/**
 * Where installer progress goes. The CLI prints it; the GUI appends it to a text area.
 *
 * <p>Warnings are tracked because an install can succeed while still being something the user
 * should look at — a NeoForge version mismatch, say — and that should not be buried in scrollback.
 */
public final class InstallLog {
    private final Consumer<String> sink;
    private int warnings;

    public InstallLog(Consumer<String> sink) {
        this.sink = sink;
    }

    public static InstallLog toStdout() {
        return new InstallLog(System.out::println);
    }

    public void info(String message) {
        sink.accept(message);
    }

    public void warn(String message) {
        warnings++;
        sink.accept("WARNING: " + message);
    }

    public void error(String message) {
        sink.accept("ERROR: " + message);
    }

    public int warnings() {
        return warnings;
    }
}
