package org.yardship.adapters.out.versionsource;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-app {@link ChangelogTemplate} lookup (ADR-0021, issue 01), built EAGERLY at startup from
 * {@link ApplicationConfigLoader}'s {@code changelog-url} config so an illegal template fails
 * boot rather than surfacing at read time — the same fail-fast posture as {@code VersionParser}
 * and the {@code http-regex} latest-source's regex validation.
 *
 * <p>This is the single lookup consumed by both the REST projection ({@code VersionController} /
 * {@code ApplicationStatus}) and the MCP slice (03) — the per-app template map is built exactly
 * once here and never duplicated into an adapter.
 */
@ApplicationScoped
@Startup
public class ChangelogTemplates {

    private final Map<String, ChangelogTemplate> templatesByApp;

    @Inject
    public ChangelogTemplates(ApplicationConfigLoader configLoader) {
        this(configLoader.apps());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public ChangelogTemplates(List<ApplicationConfigLoader.AppConfig> apps) {
        Map<String, ChangelogTemplate> templates = new HashMap<>();
        for (ApplicationConfigLoader.AppConfig app : apps) {
            app.changelogUrl().ifPresent(rawTemplate ->
                    templates.put(app.name(), buildTemplate(app, rawTemplate)));
        }
        this.templatesByApp = Map.copyOf(templates);
    }

    /** The resolved template for {@code appName}, or {@link Optional#empty()} if unconfigured. */
    public Optional<ChangelogTemplate> forApp(String appName) {
        return Optional.ofNullable(templatesByApp.get(appName));
    }

    private static ChangelogTemplate buildTemplate(
            ApplicationConfigLoader.AppConfig app, String rawTemplate) {
        Optional<CalverFormat> calverFormat = app.versionScheme() == VersionScheme.CALVER
                ? Optional.of(new CalverFormat(app.calverFormat().orElse(null)))
                : Optional.empty();
        try {
            return new ChangelogTemplate(rawTemplate, app.versionScheme(), calverFormat);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid 'changelog-url' template for app '" + app.name() + "': " + ex.getMessage(), ex);
        }
    }
}
