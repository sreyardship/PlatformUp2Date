package org.yardship.core.domain.primitives;

/**
 * One recorded configuration defect (ADR-0032): the application it belongs to, the
 * {@link ConfigErrorScope} it breaks, and a human-readable reason — normally the exact message a
 * factory (or other {@code ConfigErrorSource}) would otherwise have thrown at boot.
 *
 * <p>A domain primitive (ADR-0035), on the {@link TargetResult} precedent: same shape — a name, a
 * closed enum classifying it, and a free-text {@code reason} — and the same division of labour.
 * The structure carries no substrate vocabulary, so ADR-0005 is satisfied; substrate detail (a
 * {@code type} string, a config field name, a {@code ca-cert} path) appears inside {@code reason},
 * as prose, exactly as it already does in {@link TargetResult#reason()}.
 *
 * <p>A record, so two instances are equal exactly when {@code application}, {@code scope} and
 * {@code reason} all match.
 */
public record ConfigError(String application, ConfigErrorScope scope, String reason) {
}
