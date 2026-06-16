package org.yardship.adapters.out.versionclient;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.ports.out.ScrapeResult;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ApplicationVersionClient implements VersionRepository {

    private final Logger logger = LoggerFactory.getLogger(ApplicationVersionClient.class);

    @Inject
    ApplicationConfigLoader configLoader;

    private List<AppClients> appClients;

    /**
     * One app's two outbound endpoints, wired once from config at startup.
     */
    private record AppClients(String name, CurrentVersionClient current, GithubReleaseClient latest) {
    }

    @PostConstruct
    void buildClients() {
        appClients = configLoader.apps().stream()
                .map(app -> new AppClients(
                        app.name(),
                        build(app.current(), CurrentVersionClient.class),
                        build(app.latest(), GithubReleaseClient.class)))
                .toList();
    }

    @PreDestroy
    void closeClients() {
        for (AppClients app : appClients) {
            close(app.current());
            close(app.latest());
        }
    }

    @Override
    public ScrapeResult scrape() {
        List<VersionApplication> appList = new ArrayList<>();
        int attempted = 0;
        int failed = 0;

        for (AppClients app : appClients) {
            attempted++;
            try {
                String currentVersion = app.current().getCurrentVersion().version;
                String latestRelease = app.latest().getLatestRelease().name;

                appList.add(new VersionApplication(
                        app.name(),
                        new Version(currentVersion),
                        new Version(latestRelease)));
            } catch (Exception e) {
                failed++;
                logger.warn("Skipping app '{}' this scrape: {}", app.name(), e.getMessage());
            }
        }

        return new ScrapeResult(appList, attempted, failed);
    }

    private <T> T build(String baseUri, Class<T> clientType) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUri))
                .register(VersionResponseExceptionMapper.class)
                .build(clientType);
    }

    private void close(Object client) {
        if (client instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.warn("Failed to close version client: {}", e.getMessage());
            }
        }
    }
}
