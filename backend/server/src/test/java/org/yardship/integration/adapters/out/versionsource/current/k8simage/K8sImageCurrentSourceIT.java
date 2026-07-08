package org.yardship.integration.adapters.out.versionsource.current.k8simage;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kubernetes.client.WithKubernetesTestServer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.k8simage.K8sImageCurrentSource;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the real {@link K8sImageCurrentSource} adapter against Fabric8's
 * {@code KubernetesMockServer} (no live cluster). {@code @WithKubernetesTestServer} stands up an
 * in-memory API server and makes the injected {@link KubernetesClient} talk to it.
 *
 * <p><b>Construction seam:</b> {@code K8sImageCurrentSource} is a plain (non-CDI) per-app object,
 * constructed directly here:
 * <pre>{@code new K8sImageCurrentSource(KubernetesClient client, String namespace, String workload, String container, VersionParser parser)}</pre>
 * where {@code workload} is the {@code kind/name} string (e.g. {@code "deployment/argocd-server"}).
 * {@code version()} fetches that workload from the namespace, reads the named container's image off the
 * POD TEMPLATE ({@code spec.template.spec.containers[].image}), and parses the tag into a
 * {@link Version}.
 *
 * <p>Coverage: all three workload kinds (Deployment / StatefulSet / DaemonSet) read from the pod
 * template; the NAMED container is selected when the template has several; a digest/non-semver tag
 * surfaces as a thrown failure; a missing workload throws.
 */
@QuarkusTest
@WithKubernetesTestServer
class K8sImageCurrentSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private static final String NAMESPACE = "argocd";

    @Inject
    KubernetesClient client;

    @Test
    void readsImageTag_fromDeploymentPodTemplate() {
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("argocd-server", container("argocd-server", "quay.io/argoproj/argocd:v2.9.3")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/argocd-server", "argocd-server", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("2.9.3"), source.version());
    }

    @Test
    void readsImageTag_fromStatefulSetPodTemplate() {
        client.apps().statefulSets().inNamespace(NAMESPACE)
                .resource(statefulSet("redis", container("redis", "docker.io/library/redis:v7.2.4")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "statefulset/redis", "redis", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("7.2.4"), source.version());
    }

    @Test
    void readsImageTag_fromDaemonSetPodTemplate() {
        client.apps().daemonSets().inNamespace(NAMESPACE)
                .resource(daemonSet("node-exporter", container("node-exporter", "quay.io/prometheus/node-exporter:1.7.0")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "daemonset/node-exporter", "node-exporter", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("1.7.0"), source.version());
    }

    @Test
    void selectsTheNamedContainer_whenTemplateHasSeveral() {
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("multi",
                        container("sidecar", "envoyproxy/envoy:v1.29.0"),
                        container("app", "quay.io/argoproj/argocd:v2.10.1")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/multi", "app", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("2.10.1"), source.version());
    }

    @Test
    void readsImageTag_whenRegistryHasAPort_doesNotMistakeThePortForTheTag() {
        // registry:5000/... — the ':5000' is a registry port in an earlier path segment, not the tag.
        // The tag must still be read from the final segment after the last '/'.
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("ported", container("app", "registry.internal:5000/argoproj/argocd:v2.11.2")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/ported", "app", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("2.11.2"), source.version());
    }

    @Test
    void digestTag_throws() {
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("digest-app", container("app",
                        "quay.io/argoproj/argocd@sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/digest-app", "app", false, SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);
    }

    @Test
    void nonSemverTag_throws() {
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("latest-app", container("app", "quay.io/argoproj/argocd:latest")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/latest-app", "app", false, SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);
    }

    @Test
    void missingWorkload_throws() {
        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/does-not-exist", "app", false, SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);
    }

    // ---- strip-prerelease (slice 05) ---------------------------------------------------------

    @Test
    void stripPrerelease_false_preservesPrereleaseSegment_defaultBehaviour() {
        // stripPrerelease=false (default): a tag "1.23.0-alpine" must be reported as-is.
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("strip-false", container("app", "myrepo/app:1.23.0-alpine")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/strip-false", "app", false, SEMVER_PARSER);

        assertEquals(new SemverVersion("1.23.0-alpine"), source.version(),
                "stripPrerelease=false must preserve the prerelease segment");
    }

    @Test
    void stripPrerelease_true_stripsPrerelease_fromAlpineTag() {
        // stripPrerelease=true: a tag "1.23.0-alpine" must be reported as "1.23.0".
        // Without the strip implementation, version() returns "1.23.0-alpine" → test fails red.
        client.apps().deployments().inNamespace(NAMESPACE)
                .resource(deployment("strip-true", container("app", "myrepo/app:1.23.0-alpine")))
                .create();

        K8sImageCurrentSource source = new K8sImageCurrentSource(
                client, NAMESPACE, "deployment/strip-true", "app", true, SEMVER_PARSER);

        assertEquals(new SemverVersion("1.23.0"), source.version(),
                "stripPrerelease=true must strip the prerelease segment: 1.23.0-alpine → 1.23.0");
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static Container container(String name, String image) {
        return new ContainerBuilder().withName(name).withImage(image).build();
    }

    private static Deployment deployment(String name, Container... containers) {
        return new DeploymentBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                .withSelector(new LabelSelectorBuilder().withMatchLabels(Map.of("app", name)).build())
                .withTemplate(podTemplate(name, containers))
                .endSpec()
                .build();
    }

    private static StatefulSet statefulSet(String name, Container... containers) {
        return new StatefulSetBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                .withServiceName(name)
                .withSelector(new LabelSelectorBuilder().withMatchLabels(Map.of("app", name)).build())
                .withTemplate(podTemplate(name, containers))
                .endSpec()
                .build();
    }

    private static DaemonSet daemonSet(String name, Container... containers) {
        return new DaemonSetBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec()
                .withSelector(new LabelSelectorBuilder().withMatchLabels(Map.of("app", name)).build())
                .withTemplate(podTemplate(name, containers))
                .endSpec()
                .build();
    }

    private static io.fabric8.kubernetes.api.model.PodTemplateSpec podTemplate(String name, Container... containers) {
        return new PodTemplateSpecBuilder()
                .withNewMetadata().withLabels(Map.of("app", name)).endMetadata()
                .withNewSpec().withContainers(List.of(containers)).endSpec()
                .build();
    }
}
