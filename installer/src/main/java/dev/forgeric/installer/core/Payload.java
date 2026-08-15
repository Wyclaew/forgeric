package dev.forgeric.installer.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Extracts the loader jar that ships inside this installer.
 *
 * <p>Bundling the loader means the user downloads one file, and the loader can never be a
 * version mismatch away from the installer that placed it. It lives under {@code payload/}
 * in the installer jar and is unpacked to a temp file at install time.
 */
public final class Payload {
    private Payload() {}

    private static final String PAYLOAD_DIR = "payload/";

    /**
     * Unpacks the bundled loader jar to a temporary file.
     *
     * @return path to the extracted jar; deleted on JVM exit
     * @throws IOException when the installer was built without a payload
     */
    public static Path extractLoaderJar() throws IOException {
        String entryName = findLoaderEntry();
        String fileName = entryName.substring(PAYLOAD_DIR.length());

        Path temp = Files.createTempDirectory("forgeric-installer").resolve(fileName);
        temp.toFile().deleteOnExit();
        temp.getParent().toFile().deleteOnExit();

        try (InputStream in = Payload.class.getResourceAsStream("/" + entryName)) {
            if (in == null) {
                throw new IOException("Bundled loader " + entryName + " could not be opened");
            }
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    /** {@return the file name of the bundled loader, for display before installing} */
    public static String loaderFileName() throws IOException {
        return findLoaderEntry().substring(PAYLOAD_DIR.length());
    }

    /**
     * Locates the payload entry by scanning the installer's own jar.
     *
     * <p>The name carries a version that changes every release, so it is discovered rather
     * than hardcoded. When running from a classes directory (during development) there is no
     * jar to scan, and the caller gets a clear error instead of a confusing failure later.
     */
    private static String findLoaderEntry() throws IOException {
        Path self;
        try {
            self = Path.of(Payload.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("Could not locate the installer jar to read its payload", e);
        }

        if (!Files.isRegularFile(self)) {
            throw new IOException("Running from " + self + " rather than a jar, so no bundled loader is "
                    + "available. Build with 'gradle :installer:jar' and run the produced jar, "
                    + "or pass --loader-jar <path>.");
        }

        try (JarFile jar = new JarFile(self.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(PAYLOAD_DIR) && name.endsWith(".jar")) {
                    return name;
                }
            }
        }
        throw new IOException("This installer was built without a bundled loader jar");
    }
}
