package io.github.moacyrricardo.docusign;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies the shaded runnable artifact (spec 002 §1.3) when it is present in {@code target/}.
 * The shade step must (a) set {@code Main-Class} on the manifest and (b) merge the SDK/Jersey/HK2
 * {@code META-INF/services} SPI files so {@code new ApiClient()} works from the fat jar. Skipped
 * when the jar has not been built yet (e.g. during the {@code test} phase before {@code package}).
 */
class PackagedJarIT {

    private static final Path JAR = Path.of("target", "docusign-cli.jar");

    @Test
    void manifestPointsAtMainClass() throws IOException {
        assumeTrue(Files.isRegularFile(JAR), "shaded jar not built yet");
        try (JarFile jar = new JarFile(JAR.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest);
            assertEquals("io.github.moacyrricardo.docusign.Main",
                    manifest.getMainAttributes().getValue("Main-Class"));
        }
    }

    @Test
    void jerseyAndHk2ServiceFilesSurviveShading() throws IOException {
        assumeTrue(Files.isRegularFile(JAR), "shaded jar not built yet");
        try (JarFile jar = new JarFile(JAR.toFile())) {
            // HK2 locator + Jersey auto-discoverable feature SPI: required for ApiClient to work
            // from the fat jar. Their absence is the classic shade-drops-services failure.
            assertNotNull(jar.getJarEntry(
                            "META-INF/services/org.glassfish.jersey.internal.spi.AutoDiscoverable"),
                    "Jersey AutoDiscoverable service file missing from shaded jar");
            assertNotNull(jar.getJarEntry(
                            "META-INF/services/org.glassfish.hk2.extension.ServiceLocatorGenerator"),
                    "HK2 ServiceLocatorGenerator service file missing from shaded jar");
        }
    }

    @Test
    void bundlesTheDocuSignSdk() throws IOException {
        assumeTrue(Files.isRegularFile(JAR), "shaded jar not built yet");
        try (JarFile jar = new JarFile(JAR.toFile())) {
            assertNotNull(jar.getJarEntry("com/docusign/esign/client/ApiClient.class"));
            assertTrue(jar.getJarEntry("jakarta/ws/rs/core/GenericType.class") != null,
                    "JAX-RS API must be bundled (SDK ApiClient needs it at runtime)");
        }
    }
}
