package org.yardship.adapters.out.versionsource.current.k8simage;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;
import java.util.List;

/**
 * The {@code k8s-image} {@link CurrentVersionSource}: derives an app's current (deployed) version
 * from the container image tag on a workload's pod template, read via the Kubernetes API.
 *
 * <p>A plain (non-CDI), per-app object wrapping the shared, CDI-managed {@link KubernetesClient}.
 * {@code workload} is a {@code kind/name} string ({@code deployment/argocd-server},
 * {@code statefulset/redis}, {@code daemonset/node-exporter}); {@link #version()} fetches that workload
 * from {@code namespace}, selects the container named {@code container} off its pod template
 * ({@code spec.template.spec.containers[].image}) and parses the tag into a {@link VersionValue}.
 *
 * <p>When {@code stripPrerelease} is {@code true}, the prerelease segment of the parsed version is
 * cleared before it is returned (e.g. {@code 1.23.0-alpine} → {@code 1.23.0}), mirroring the
 * behaviour of the {@code http} current source's {@code strip-prerelease} option (ADR-0014). This
 * allows a cluster running an alpine-flavour image ({@code app:1.23.0-alpine}) to compare as
 * {@code 1.23.0} against an {@code oci-registry} latest source also configured with
 * {@code strip-prerelease: true}.
 *
 * <p>A missing workload, an unknown kind, a missing named container, a digest reference, or a
 * non-semver tag all THROW, so the scrape loop isolates and counts that app's failure (same
 * isolation as an unreachable HTTP endpoint).
 */
public class K8sImageCurrentSource implements CurrentVersionSource, Closeable {

    private final KubernetesClient client;
    private final String namespace;
    private final String workload;
    private final String container;
    private final boolean stripPrerelease;
    private final VersionParser parser;

    /**
     * Primary constructor. {@code stripPrerelease} mirrors the {@code http} current source's flag:
     * when {@code true}, the prerelease segment of the parsed version is cleared before reporting.
     * The {@code parser} produces the {@link VersionValue} in the app's scheme.
     */
    public K8sImageCurrentSource(KubernetesClient client, String namespace, String workload,
                                 String container, boolean stripPrerelease, VersionParser parser) {
        this.client = client;
        this.namespace = namespace;
        this.workload = workload;
        this.container = container;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue version() {
        VersionValue version = versionFromImage(imageOfNamedContainer(podTemplate()));
        return stripPrerelease ? version.withoutPreRelease() : version;
    }

    private PodTemplateSpec podTemplate() {
        int slash = workload.indexOf('/');
        if (slash < 0) {
            throw new IllegalStateException(
                    "Workload '" + workload + "' must be a 'kind/name' reference.");
        }
        String kind = workload.substring(0, slash).toLowerCase();
        String name = workload.substring(slash + 1);

        var template = switch (kind) {
            case "deployment" -> {
                var resource = client.apps().deployments().inNamespace(namespace).withName(name).get();
                yield resource == null ? null : resource.getSpec().getTemplate();
            }
            case "statefulset" -> {
                var resource = client.apps().statefulSets().inNamespace(namespace).withName(name).get();
                yield resource == null ? null : resource.getSpec().getTemplate();
            }
            case "daemonset" -> {
                var resource = client.apps().daemonSets().inNamespace(namespace).withName(name).get();
                yield resource == null ? null : resource.getSpec().getTemplate();
            }
            default -> throw new IllegalStateException(
                    "Unknown workload kind '" + kind + "' in workload '" + workload + "'.");
        };
        if (template == null) {
            throw new IllegalStateException(
                    "Workload '" + workload + "' not found in namespace '" + namespace + "'.");
        }
        return template;
    }

    private String imageOfNamedContainer(PodTemplateSpec template) {
        List<Container> containers = template.getSpec().getContainers();
        return containers.stream()
                .filter(c -> container.equals(c.getName()))
                .map(Container::getImage)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Container '" + container + "' not found in workload '" + workload + "'."));
    }

    /**
     * Extract the {@link VersionValue} from a container image reference: strip the registry/repo prefix
     * (everything up to and including the last {@code /}), then take the tag as the part after the
     * last {@code :} in that final segment. Keeping the tag scoped to the final segment means a
     * registry port like {@code registry:5000/app:1.2.3} is unambiguous — the {@code :5000} lives in
     * an earlier path segment. {@code v}-stripping and semver validation are delegated to
     * the app's {@link VersionParser}, so a digest ({@code @sha256:…}) or a non-semver tag ({@code latest},
     * {@code stable}) throws.
     */
    private VersionValue versionFromImage(String image) {
        String lastSegment = image.substring(image.lastIndexOf('/') + 1);
        int tagSeparator = lastSegment.lastIndexOf(':');
        if (tagSeparator < 0) {
            throw new IllegalStateException("Image '" + image + "' has no tag.");
        }
        return parser.parse(lastSegment.substring(tagSeparator + 1));
    }

    /**
     * No-op: the {@link KubernetesClient} is CDI-managed and shared across all {@code k8s-image}
     * apps, so this per-app source must never close it — doing so would break every other app.
     */
    @Override
    public void close() {
        // The shared, CDI-managed client is owned by the container, not by this per-app source.
    }
}
