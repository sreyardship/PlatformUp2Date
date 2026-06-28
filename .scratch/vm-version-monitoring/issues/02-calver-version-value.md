# [02] Calver as a VersionValue

Status: done
Type: AFK

## Plan
See `../plan.md`. Settles [ADR-0015](../../../docs/adr/0015-calver-as-a-first-class-version-scheme.md).

## What to build
The second `VersionValue` implementation: genuine calendar versioning with its own
structure, selectable per app, thoroughly tested across the full calver.org token set.

- `CalverFormat`: parse a `calver-format` string into an ordered token list over the
  full calver.org vocabulary (`YYYY YY 0Y MM 0M WW 0W DD 0D MAJOR MINOR MICRO
  MODIFIER`) plus literal separators. Knows how to match/parse a raw version string
  into ordered components and the `Diff` category of each token.
- `CalverVersion`: holds the parsed, ordered components and the **original string for
  display** (`value()` returns it verbatim — `24.04` stays `24.04`). Ordering is
  positional-numeric in token order; `MODIFIER` (the only non-numeric token) orders
  prerelease-style (a build with a modifier sorts below the same build without).
- `diff` grades by **token category**: year → MAJOR; month/week/day → MINOR;
  MICRO/MODIFIER → PATCH; and the embedded `MAJOR`/`MINOR` tokens map to their names.
  When several tokens differ, the grade is the most-significant category among them.
- `VersionParser` learns `CALVER`: builds a `CalverVersion` against the app's
  `CalverFormat`. A `calver` scheme with a missing or invalid `calver-format` fails
  fast at parser construction (startup), consistent with the resolver's existing
  fail-fast on bad config.
- Add the app-level `calver-format` config field (required when `version-scheme:
  calver`).

The drift category map:

| Category | Tokens |
|---|---|
| `MAJOR` | `YYYY` `YY` `0Y`, and the `MAJOR` token |
| `MINOR` | `MM` `0M` `WW` `0W` `DD` `0D`, and the `MINOR` token |
| `PATCH` | `MICRO`, `MODIFIER` |

## Architectural surface
- Use cases: none new.
- Ports: none new (`VersionParser` extended).
- Adapters: `ApplicationConfigLoader` gains `calver-format`; `VersionSourceResolver`
  passes scheme + format into the per-app parser (validating format fail-fast).

## Acceptance criteria
- [ ] `CalverFormat` parses every calver.org token, padded and unpadded, with literal
      separators; an unknown token or malformed format string is rejected.
- [ ] `CalverVersion.value()` returns the original string unchanged (display fidelity).
- [ ] Ordering is correct: `24.04 < 24.10`, `23.05 < 23.05.5`, three-digit short
      years (`YY` → `106`), and `MODIFIER` orders prerelease-style.
- [ ] `diff` returns the token-category grade, most-significant-category-wins when
      multiple tokens differ; a date-only format never yields PATCH.
- [ ] `VersionParser` builds `CalverVersion` for `calver`; `calver` without a valid
      `calver-format` fails fast at startup.
- [ ] A `calver` app whose source returns a string not matching its format fails that
      app's scrape (isolated), not the boot.
- [ ] Unit tests cover the full token set and every case above.

## Blocked by
`01-versionvalue-and-version-scheme.md`.
