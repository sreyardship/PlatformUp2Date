package org.yardship.cli.outcome;

import java.util.Optional;

/**
 * The result of checking ONE surface (regex/pointer/changelog/calver) for ONE app in the
 * {@code config} gate (issue 06). Three-way {@link Status}, not a plain
 * {@code Optional<ValidationOutcome>}, because "no outcome" is ambiguous on its own: it could mean
 * "this surface isn't configured for this app" ({@link Status#NOT_APPLICABLE} — e.g. an app whose
 * {@code latest.type} is {@code github-release}, which has no CLI-transparent validator) or "this
 * surface IS configured but {@code --offline} suppressed the live fetch it needs"
 * ({@link Status#SKIPPED_OFFLINE} — regex/pointer only; changelog/calver are pure-function and
 * never skipped). Neither of those is a failure; only {@link Status#RAN} with a non-zero
 * {@link ValidationOutcome#exitCode()} is.
 *
 * @param surface which of the four surfaces this result is for.
 * @param status  whether the check ran, was skipped for {@code --offline}, or was not applicable.
 * @param outcome the underlying {@link ValidationOutcome}; present only when {@code status == RAN}.
 */
public record SurfaceResult(Surface surface, Status status, Optional<ValidationOutcome> outcome) {

    public SurfaceResult {
        if (status == Status.RAN && outcome.isEmpty()) {
            throw new IllegalArgumentException("A RAN SurfaceResult must carry an outcome.");
        }
        if (status != Status.RAN && outcome.isPresent()) {
            throw new IllegalArgumentException(
                    "A " + status + " SurfaceResult must not carry an outcome (nothing ran).");
        }
    }

    /** Which of the four per-surface validators this result is for. */
    public enum Surface {
        REGEX, POINTER, CHANGELOG, CALVER
    }

    public enum Status {
        /** The validator actually ran; {@link #outcome()} carries its result. */
        RAN,
        /** The surface IS configured but required a live fetch suppressed by {@code --offline}. */
        SKIPPED_OFFLINE,
        /** Nothing is configured for this app that this surface could validate. */
        NOT_APPLICABLE
    }

    public static SurfaceResult ran(Surface surface, ValidationOutcome outcome) {
        return new SurfaceResult(surface, Status.RAN, Optional.of(outcome));
    }

    public static SurfaceResult skippedOffline(Surface surface) {
        return new SurfaceResult(surface, Status.SKIPPED_OFFLINE, Optional.empty());
    }

    public static SurfaceResult notApplicable(Surface surface) {
        return new SurfaceResult(surface, Status.NOT_APPLICABLE, Optional.empty());
    }

    /** {@code true} only when this surface actually ran and its outcome signals failure. */
    public boolean isFailure() {
        return status == Status.RAN && outcome.orElseThrow().exitCode() != 0;
    }
}
