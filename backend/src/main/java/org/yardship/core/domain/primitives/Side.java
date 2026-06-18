package org.yardship.core.domain.primitives;

/**
 * Which side(s) of an app's version pair a targeted scrape should refresh.
 *
 * <p>{@code CURRENT}/{@code LATEST} refresh only that leg of the pair, splicing the result over the
 * existing snapshot entry and leaving the other leg untouched. {@code BOTH} replaces the whole pair.
 * A single-side target for an app not yet in the snapshot is upgraded to {@code BOTH} by the service
 * (you cannot persist half a {@link VersionApplication}) — see {@link TargetResult#side()}.
 */
public enum Side {
    CURRENT,
    LATEST,
    BOTH
}
