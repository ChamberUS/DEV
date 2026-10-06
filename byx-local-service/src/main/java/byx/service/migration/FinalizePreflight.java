package byx.service.migration;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Finalize conditions, checked before any cutover mutation. No OTP or network calls. */
public final class FinalizePreflight {
    public record Conditions(boolean trusted, boolean verified, boolean safetyWindow,
            boolean privateCapabilitiesAllowed, boolean profileCurrent, boolean enabledAdmin,
            boolean secondFactorConfigured) { }

    public static void require(Conditions c) throws MigrationException {
        if (!c.trusted()) throw new MigrationException("authority_untrusted");
        if (!c.verified()) throw new MigrationException("migration_verify_failed");
        if (!c.safetyWindow()) throw new MigrationException("safety_window_inactive");
        if (c.privateCapabilitiesAllowed()) throw new MigrationException("private_gate_open");
        if (!c.profileCurrent()) throw new MigrationException("provisioning_profile_expired_or_unavailable");
        if (!c.enabledAdmin()) throw new MigrationException("enabled_admin_required");
        if (!c.secondFactorConfigured()) throw new MigrationException("second_factor_unavailable");
    }

    static boolean currentPackagedProfile(Clock clock) {
        Path decoded = null;
        try {
            String executable = ProcessHandle.current().info().command().orElse(null);
            if (executable == null) return false;
            Path bundle = Path.of(executable).toAbsolutePath();
            while (bundle != null && !bundle.getFileName().toString().endsWith(".app")) bundle = bundle.getParent();
            if (bundle == null) return false;
            Path profile = bundle.resolve("Contents/embedded.provisionprofile");
            if (!Files.isRegularFile(profile, LinkOption.NOFOLLOW_LINKS)) return false;
            decoded = Files.createTempFile("byx-profile-metadata-", ".plist");
            var process = new ProcessBuilder("/usr/bin/security", "cms", "-D", "-i", profile.toString())
                    .redirectOutput(decoded.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return false; }
            if (process.exitValue() != 0 || Files.size(decoded) > 1_048_576) return false;
            return profileCurrent(Files.readAllBytes(decoded), clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (decoded != null) try { Files.deleteIfExists(decoded); } catch (java.io.IOException ignored) { }
        }
    }

    static boolean profileCurrent(byte[] plist, Instant now) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var keys = factory.newDocumentBuilder().parse(new ByteArrayInputStream(plist)).getElementsByTagName("key");
            for (int i = 0; i < keys.getLength(); i++) {
                if (!keys.item(i).getTextContent().equals("ExpirationDate")) continue;
                var next = keys.item(i).getNextSibling();
                while (next != null && next.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) next = next.getNextSibling();
                return next != null && next.getNodeName().equals("date") && Instant.parse(next.getTextContent()).isAfter(now);
            }
        } catch (Exception e) { return false; }
        return false;
    }
    private FinalizePreflight() { }
}
