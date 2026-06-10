package io.github.moacyrricardo.docusign.cli;

import picocli.CommandLine.IVersionProvider;

/**
 * Supplies {@code --version} output from the jar manifest's {@code Implementation-Version}
 * (spec 002 §3.1). Falls back to a placeholder when run outside a packaged jar (e.g. tests).
 */
public final class ManifestVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        Package pkg = ManifestVersionProvider.class.getPackage();
        String version = (pkg != null) ? pkg.getImplementationVersion() : null;
        if (version == null || version.isBlank()) {
            version = "0.1.0-SNAPSHOT (dev)";
        }
        return new String[] { "docusign-cli " + version };
    }
}
