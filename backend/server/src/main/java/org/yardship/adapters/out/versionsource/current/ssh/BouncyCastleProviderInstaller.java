package org.yardship.adapters.out.versionsource.current.ssh;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;

/**
 * Registers the BouncyCastle JCA provider by name ({@code "BC"}) at application startup.
 *
 * <p>MINA SSHD resolves several security entities through the named-provider path
 * ({@code KeyPairGenerator/Signature.getInstance(algorithm, "BC")}). On the JVM MINA registers the
 * provider itself, but in the GraalVM native image that registration does not stick, so the
 * {@code ssh-os-release} source fails at connect time with {@code NoSuchProviderException: no such
 * provider: BC} — isolated to that app by the per-call lifecycle, but still a scrape failure.
 *
 * <p>Registering BouncyCastle once at boot (GraalVM supports runtime {@code Security.addProvider})
 * makes the named lookups resolve in native. See {@code docs/adr/0018}. This is the
 * BouncyCastle-inclusive path; the follow-on slice may drop it by disabling MINA's BC registrar.
 */
@ApplicationScoped
public class BouncyCastleProviderInstaller {

    void onStart(@Observes StartupEvent event) {
        // Only register in the native image. On the JVM MINA registers BouncyCastle itself, and
        // registering it again here would also run inside @QuarkusTest JVMs — loading BC under the
        // Quarkus classloader and breaking MINA's ed25519 casts in the plain in-process SSH IT.
        // GraalVM sets this system property to "runtime" only in a running native image (it is the
        // mechanism behind ImageInfo.inImageRuntimeCode()); avoiding the SDK keeps it off the
        // compile classpath.
        if (!"runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return;
        }
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }
}
