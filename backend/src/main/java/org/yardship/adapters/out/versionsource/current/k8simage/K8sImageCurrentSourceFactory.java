package org.yardship.adapters.out.versionsource.current.k8simage;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Factory for the {@code k8s-image} current-version kind. Discovered as a CDI bean; validates its
 * own config fragment ({@code namespace}, {@code workload} and {@code container} are all required and
 * non-blank) and constructs a per-app {@link K8sImageCurrentSource}.
 *
 * <p>The injected {@link KubernetesClient} is auto-configured in-cluster from the backend's
 * ServiceAccount token — there is no client config in the app. Validation runs BEFORE any client use
 * so a bad config fails fast without touching the cluster.
 */
@ApplicationScoped
public class K8sImageCurrentSourceFactory implements CurrentVersionSourceFactory {

    private final KubernetesClient client;

    @Inject
    public K8sImageCurrentSourceFactory(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public String type() {
        return "k8s-image";
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
        String namespace = required(cfg.namespace(), "namespace");
        String workload = required(cfg.workload(), "workload");
        String container = required(cfg.container(), "container");
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);
        return new K8sImageCurrentSource(client, namespace, workload, container, stripPrerelease);
    }

    private static String required(Optional<String> value, String field) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(missingField(field));
    }

    private static Supplier<IllegalArgumentException> missingField(String field) {
        return () -> new IllegalArgumentException(
                "The 'k8s-image' current source requires a non-blank '" + field + "'.");
    }
}
