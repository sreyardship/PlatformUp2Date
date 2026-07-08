package org.yardship.cli.port;

import java.util.List;

/**
 * Reads a {@code platform-config.yaml} into {@link AppConfig} records for the {@code config} gate
 * (issue 06). The backend's {@code ApplicationConfigLoader} is Quarkus/SmallRye-bound (a
 * {@code @ConfigMapping} interface materialised by the Quarkus runtime) and cannot be reused here —
 * {@code :cli} is plain picocli, not Quarkus (ADR-0026) — so this port is a CLI-owned equivalent,
 * backed by a plain YAML parser ({@code YamlAppConfigReader}).
 */
public interface AppConfigReader {

    /**
     * @return every app declared under {@code platform-config.apps}, in file order.
     * @throws ConfigReadException if the file cannot be read, or its content is not valid YAML, or
     *         it does not match the expected {@code platform-config.apps[]} shape closely enough to
     *         extract the fields {@link AppConfig} models (e.g. an app entry missing the required
     *         {@code name}/{@code current.type}/{@code latest.type}).
     */
    List<AppConfig> apps();

    /** Raised by an {@link AppConfigReader} when the config file cannot be read or parsed. */
    class ConfigReadException extends RuntimeException {
        public ConfigReadException(String message) {
            super(message);
        }

        public ConfigReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
