package org.yardship.unit.adapters.out.versionsource;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.K8sImageCurrentSourceFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link K8sImageCurrentSourceFactory} — the factory for the {@code k8s-image}
 * current-version kind. Verifies its discriminator and its own config-fragment validation:
 * {@code namespace}, {@code workload}, and {@code container} are all required and non-blank.
 *
 * <p><b>Test seam:</b> the factory is {@code @ApplicationScoped} and injects a CDI-provided
 * {@link KubernetesClient}. To keep this a true unit test, the implementer must accept the client via
 * constructor injection so a test can pass a Mockito mock:
 * <pre>{@code K8sImageCurrentSourceFactory(KubernetesClient client)}</pre>
 * Validation MUST run before any client call, so on every error path the mock is never touched
 * ({@code verifyNoInteractions}). Construction of the live source is exercised in
 * {@code K8sImageCurrentSourceIT}.
 */
class K8sImageCurrentSourceFactoryTests {

    private final KubernetesClient client = Mockito.mock(KubernetesClient.class);
    private final K8sImageCurrentSourceFactory factory = new K8sImageCurrentSourceFactory(client);

    @Test
    void type_isK8sImage() {
        assertEquals("k8s-image", factory.type());
    }

    @Test
    void create_buildsASource_whenAllFieldsPresent() {
        assertNotNull(factory.create(source(
                Optional.of("argocd"),
                Optional.of("deployment/argocd-server"),
                Optional.of("argocd-server"))));
    }

    @Test
    void create_rejectsAbsentNamespace_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.empty(),
                        Optional.of("deployment/argocd-server"),
                        Optional.of("argocd-server"))));
        assertTrue(ex.getMessage().toLowerCase().contains("namespace"),
                "the validation error must name the missing 'namespace'; was: " + ex.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void create_rejectsBlankNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.of("   "),
                        Optional.of("deployment/argocd-server"),
                        Optional.of("argocd-server"))));
        verifyNoInteractions(client);
    }

    @Test
    void create_rejectsAbsentWorkload_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.of("argocd"),
                        Optional.empty(),
                        Optional.of("argocd-server"))));
        assertTrue(ex.getMessage().toLowerCase().contains("workload"),
                "the validation error must name the missing 'workload'; was: " + ex.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void create_rejectsBlankWorkload() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.of("argocd"),
                        Optional.of("  "),
                        Optional.of("argocd-server"))));
        verifyNoInteractions(client);
    }

    @Test
    void create_rejectsAbsentContainer_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.of("argocd"),
                        Optional.of("deployment/argocd-server"),
                        Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("container"),
                "the validation error must name the missing 'container'; was: " + ex.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void create_rejectsBlankContainer() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(
                        Optional.of("argocd"),
                        Optional.of("deployment/argocd-server"),
                        Optional.of(""))));
        verifyNoInteractions(client);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> namespace, Optional<String> workload, Optional<String> container) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "k8s-image";
            }

            @Override
            public Optional<String> url() {
                return Optional.empty();
            }

            @Override
            public Optional<String> namespace() {
                return namespace;
            }

            @Override
            public Optional<String> workload() {
                return workload;
            }

            @Override
            public Optional<String> container() {
                return container;
            }

            @Override
            public Optional<String> versionKey() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return Optional.empty();
            }
        };
    }
}
