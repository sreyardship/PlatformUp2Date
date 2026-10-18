package org.yardship.integration.adapters.out.versionsource.latest;

import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;

import java.util.Map;
import java.util.Optional;

/**
 * A hand-built latest-leg {@link ApplicationConfigLoader.VersionSource} fragment for the latest-kind
 * integration suites, which build their source end to end through the kind's factory the way
 * {@code VersionSourceResolver} does. Every field not set reads as absent. One fixture for the three
 * kinds, so the shared transport keys ({@code auth}, {@code ca-cert},
 * {@code insecure-skip-tls-verify}) are spelled the same way in each suite.
 */
public final class LatestSourceFragment implements ApplicationConfigLoader.VersionSource {

    private final String type;
    private Optional<String> url = Optional.empty();
    private Optional<String> regex = Optional.empty();
    private Optional<String> repo = Optional.empty();
    private Optional<String> registry = Optional.empty();
    private Optional<Integer> pageSize = Optional.empty();
    private Optional<Integer> maxTags = Optional.empty();
    private Optional<String> prereleaseFilter = Optional.empty();
    private Optional<Boolean> stripPrerelease = Optional.empty();
    private Optional<Auth> auth = Optional.empty();
    private Optional<String> caCert = Optional.empty();
    private Optional<Boolean> insecureSkipTlsVerify = Optional.empty();

    private LatestSourceFragment(String type) {
        this.type = type;
    }

    public static LatestSourceFragment ofType(String type) {
        return new LatestSourceFragment(type);
    }

    public LatestSourceFragment url(String value) {
        this.url = Optional.of(value);
        return this;
    }

    public LatestSourceFragment regex(String value) {
        this.regex = Optional.of(value);
        return this;
    }

    public LatestSourceFragment repo(String value) {
        this.repo = Optional.of(value);
        return this;
    }

    public LatestSourceFragment registry(String value) {
        this.registry = Optional.of(value);
        return this;
    }

    public LatestSourceFragment pageSize(int value) {
        this.pageSize = Optional.of(value);
        return this;
    }

    public LatestSourceFragment maxTags(int value) {
        this.maxTags = Optional.of(value);
        return this;
    }

    public LatestSourceFragment prereleaseFilter(String value) {
        this.prereleaseFilter = Optional.of(value);
        return this;
    }

    public LatestSourceFragment stripPrerelease(boolean value) {
        this.stripPrerelease = Optional.of(value);
        return this;
    }

    public LatestSourceFragment basicAuth(String username, String password) {
        this.auth = Optional.of(auth("basic", Optional.of(username), Optional.of(password), Optional.empty()));
        return this;
    }

    public LatestSourceFragment bearerAuth(String token) {
        this.auth = Optional.of(auth("bearer", Optional.empty(), Optional.empty(), Optional.of(token)));
        return this;
    }

    public LatestSourceFragment caCert(String path) {
        this.caCert = Optional.of(path);
        return this;
    }

    public LatestSourceFragment insecureSkipTlsVerify(boolean value) {
        this.insecureSkipTlsVerify = Optional.of(value);
        return this;
    }

    @Override public Optional<String> type() { return Optional.of(type); }
    @Override public Optional<String> url() { return url; }
    @Override public Optional<String> regex() { return regex; }
    @Override public Optional<String> repo() { return repo; }
    @Override public Optional<String> registry() { return registry; }
    @Override public Optional<Integer> pageSize() { return pageSize; }
    @Override public Optional<Integer> maxTags() { return maxTags; }
    @Override public Optional<String> prereleaseFilter() { return prereleaseFilter; }
    @Override public Optional<Boolean> stripPrerelease() { return stripPrerelease; }
    @Override public Optional<Auth> auth() { return auth; }
    @Override public Optional<String> caCert() { return caCert; }
    @Override public Optional<Boolean> insecureSkipTlsVerify() { return insecureSkipTlsVerify; }

    @Override public Map<String, String> labels() { return Map.of(); }
    @Override public Optional<String> versionHeader() { return Optional.empty(); }
    @Override public Optional<String> versionKey() { return Optional.empty(); }
    @Override public Optional<String> metric() { return Optional.empty(); }
    @Override public Optional<String> versionLabel() { return Optional.empty(); }
    @Override public Optional<String> namespace() { return Optional.empty(); }
    @Override public Optional<String> workload() { return Optional.empty(); }
    @Override public Optional<String> container() { return Optional.empty(); }
    @Override public Optional<String> host() { return Optional.empty(); }
    @Override public Optional<Integer> port() { return Optional.empty(); }
    @Override public Optional<String> user() { return Optional.empty(); }
    @Override public Optional<String> privateKey() { return Optional.empty(); }
    @Override public Optional<String> privateKeyFile() { return Optional.empty(); }
    @Override public Optional<String> hostKey() { return Optional.empty(); }
    @Override public Optional<String> knownHosts() { return Optional.empty(); }
    @Override public Optional<String> releaseField() { return Optional.empty(); }

    private static Auth auth(String type, Optional<String> username, Optional<String> password, Optional<String> token) {
        return new Auth() {
            @Override public Optional<String> type() { return Optional.of(type); }
            @Override public Optional<String> username() { return username; }
            @Override public Optional<String> password() { return password; }
            @Override public Optional<String> token() { return token; }
            @Override public Optional<String> tokenFile() { return Optional.empty(); }
        };
    }
}
