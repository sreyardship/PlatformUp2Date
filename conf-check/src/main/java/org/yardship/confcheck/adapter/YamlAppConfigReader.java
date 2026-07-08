package org.yardship.confcheck.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.yardship.confcheck.port.AppConfig;
import org.yardship.confcheck.port.AppConfigReader;
import org.yardship.core.domain.primitives.VersionScheme;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Driven {@link AppConfigReader} adapter that parses a real {@code platform-config.yaml} file off
 * disk into {@link AppConfig} records for the {@code config} gate (issue 06), using a standalone
 * Jackson YAML mapper ({@code jackson-dataformat-yaml}) — {@code :cli} must not depend on
 * {@code :backend}'s Quarkus/SmallRye {@code ApplicationConfigLoader}, so this is a plain,
 * hand-rolled equivalent covering only the fields {@link AppConfig} models. See
 * {@code docs/samples/platform-config.yaml} and {@code ApplicationConfigLoader} (backend) for the
 * exact top-level {@code platform-config.apps[]} shape (kebab-case YAML keys) this must bind.
 *
 * <p>Parses into kebab-case-annotated intermediate DTOs ({@link RootDto}/{@link AppDto}/
 * {@link CurrentDto}/{@link LatestDto}) rather than deserializing straight into the immutable
 * {@link AppConfig} record, since Jackson's record support wants either a default-constructor-
 * friendly shape or a {@code @JsonCreator}, and a mutable DTO makes the kebab-case-to-camelCase
 * translation and the "apply the {@code version-scheme} default here" step (see
 * {@link AppConfig}'s javadoc) straightforward to read.
 */
public final class YamlAppConfigReader implements AppConfigReader {

    private final Path configFile;

    public YamlAppConfigReader(Path configFile) {
        this.configFile = configFile;
    }

    @Override
    public List<AppConfig> apps() {
        RootDto root = readRoot();
        List<AppDto> appDtos = requireApps(root);

        List<AppConfig> apps = new ArrayList<>();
        for (AppDto appDto : appDtos) {
            apps.add(toAppConfig(appDto));
        }
        return apps;
    }

    private RootDto readRoot() {
        try {
            YAMLMapper mapper = new YAMLMapper();
            mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(configFile.toFile(), RootDto.class);
        } catch (MismatchedInputException e) {
            throw new ConfigReadException(
                    "'" + configFile + "' does not match the expected platform-config.apps[] shape: "
                            + e.getMessage(), e);
        } catch (IOException e) {
            throw new ConfigReadException(
                    "Could not read/parse config file '" + configFile + "': " + e.getMessage(), e);
        }
    }

    private List<AppDto> requireApps(RootDto root) {
        if (root == null || root.platformConfig == null || root.platformConfig.apps == null) {
            throw new ConfigReadException(
                    "'" + configFile + "' is missing the required 'platform-config.apps' section.");
        }
        return root.platformConfig.apps;
    }

    private AppConfig toAppConfig(AppDto appDto) {
        requireField(appDto.name, "name");
        CurrentDto current = appDto.current;
        LatestDto latest = appDto.latest;
        if (current == null) {
            throw missingField("current.type");
        }
        if (latest == null) {
            throw missingField("latest.type");
        }
        requireField(current.type, "current.type");
        requireField(latest.type, "latest.type");

        VersionScheme versionScheme = appDto.versionScheme == null
                ? VersionScheme.SEMVER
                : VersionScheme.valueOf(appDto.versionScheme.trim().toUpperCase());

        return new AppConfig(
                appDto.name,
                versionScheme,
                Optional.ofNullable(appDto.calverFormat),
                Optional.ofNullable(appDto.changelogUrl),
                current.type,
                Optional.ofNullable(current.url),
                Optional.ofNullable(current.versionKey),
                Boolean.TRUE.equals(current.stripPrerelease),
                latest.type,
                Optional.ofNullable(latest.url),
                Optional.ofNullable(latest.regex));
    }

    private void requireField(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw missingField(fieldName);
        }
    }

    private ConfigReadException missingField(String fieldName) {
        return new ConfigReadException(
                "'" + configFile + "' has an app entry missing the required field '" + fieldName + "'.");
    }

    private static final class RootDto {
        @JsonProperty("platform-config")
        public PlatformConfigDto platformConfig;
    }

    private static final class PlatformConfigDto {
        public List<AppDto> apps;
    }

    private static final class AppDto {
        public String name;

        @JsonProperty("version-scheme")
        public String versionScheme;

        @JsonProperty("calver-format")
        public String calverFormat;

        @JsonProperty("changelog-url")
        public String changelogUrl;

        public CurrentDto current;
        public LatestDto latest;
    }

    private static final class CurrentDto {
        public String type;
        public String url;

        @JsonProperty("version-key")
        public String versionKey;

        @JsonProperty("strip-prerelease")
        public Boolean stripPrerelease;
    }

    private static final class LatestDto {
        public String type;
        public String url;
        public String regex;
    }
}
