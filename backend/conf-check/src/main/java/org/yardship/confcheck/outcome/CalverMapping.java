package org.yardship.confcheck.outcome;

import java.util.List;

/**
 * The {@code calver} subcommand's report payload: the token-symbol -> displayed-value pairs
 * produced by resolving a sample {@code --version} against a {@code --format}, in the format's
 * declared token order (e.g. {@code YY.0M.MICRO} + {@code 23.05.5} -> {@code YY=23, 0M=05,
 * MICRO=5}).
 *
 * <p>A declared token that is trailing-absent from the actual {@code --version} string (mirrors
 * {@link org.yardship.core.domain.primitives.ChangelogTemplate}'s doc example: format
 * {@code YY.0M.MICRO} but version {@code "23.05"}) is OMITTED from {@link #tokens()} rather than
 * shown with an empty value. This differs from {@code ChangelogTemplate#resolve}, which renders
 * an absent token as {@code ""} because it is substituting into a URL literal where a blank
 * segment is still a coherent (if odd) URL. Here there is no surrounding literal to preserve —
 * {@code "MICRO="} with nothing after the {@code =} would read as a rendering bug, not as "this
 * token has no value in this instance" — so omission is the more legible report line for a
 * human reading the token=value list. Order among the remaining tokens still matches the
 * format's declaration order.
 */
public record CalverMapping(List<TokenDisplay> tokens) {

    public CalverMapping {
        tokens = List.copyOf(tokens);
    }

    /** One token's format-string symbol (e.g. {@code "0M"}, not the enum name {@code ZERO_M}) and its displayed value. */
    public record TokenDisplay(String symbol, String displayedValue) {}
}
