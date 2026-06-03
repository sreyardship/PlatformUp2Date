package org.yardship.adapters.out.versionclient;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ApplicationVersionClient implements VersionRepository {

    private final Logger logger = LoggerFactory.getLogger(ApplicationVersionClient.class);

    @Inject
    ApplicationConfigLoader configLoader;

    @Override
    public List<VersionApplication> getAllVersionApplications() {
        List<VersionApplication> appList = new ArrayList<>();

        configLoader.apps().forEach(appConfig -> {
            String currentVersion = getCurrentVersion(appConfig.current());
            String latestRelease = getLatestRelease(appConfig.latest());

            appList.add(new VersionApplication(
                    appConfig.name(),
                    new Version(currentVersion),
                    new Version(latestRelease))
            );
        });

        return appList;
    }

    private String getCurrentVersion(String baseUri) {
        CurrentVersionClient currentClient = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUri))
                .build(CurrentVersionClient.class);

        CurrentVersionResponseDTO currentResponse = currentClient.getCurrentVersion();
        return currentResponse.version;
    }

    private String getLatestRelease(String baseUri) {
        GithubReleaseClient githubReleaseClient = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUri))
                .build(GithubReleaseClient.class);

        GithubReleaseResponseDTO githubResponse = githubReleaseClient.getLatestRelease();
        return githubResponse.name;
    }
}
