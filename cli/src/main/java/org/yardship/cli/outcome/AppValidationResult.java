package org.yardship.cli.outcome;

import java.util.List;

/**
 * The aggregate result of validating ONE app's config in the {@code config} gate (issue 06): the
 * app's name plus one {@link SurfaceResult} per surface considered for it
 * (regex/pointer/changelog/calver — always all four, each either
 * {@code RAN}/{@code SKIPPED_OFFLINE}/{@code NOT_APPLICABLE}).
 *
 * @param appName  the app's {@code name}.
 * @param surfaces exactly one {@link SurfaceResult} per {@link SurfaceResult.Surface}, in a stable
 *                 order (regex, pointer, changelog, calver) for deterministic reporting.
 */
public record AppValidationResult(String appName, List<SurfaceResult> surfaces) {

    /** {@code true} iff at least one surface {@link SurfaceResult#isFailure() failed}. */
    public boolean isFailure() {
        return surfaces.stream().anyMatch(SurfaceResult::isFailure);
    }
}
