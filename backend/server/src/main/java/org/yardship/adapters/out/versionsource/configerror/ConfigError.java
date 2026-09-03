package org.yardship.adapters.out.versionsource.configerror;

/**
 * One recorded configuration defect (ADR-0032): the application it belongs to, the
 * {@link ConfigErrorScope} it breaks, and a human-readable reason — normally the exact message a
 * factory (or other {@link ConfigErrorSource}) would otherwise have thrown at boot.
 *
 * <p>A server adapter type, not a domain primitive (ADR-0005 keeps substrate vocabulary — {@code
 * type} strings, config field names — out of {@code :backend:domain}).
 *
 * <p>A record, so two instances are equal exactly when {@code application}, {@code scope} and
 * {@code reason} all match.
 */
public record ConfigError(String application, ConfigErrorScope scope, String reason) {
}
