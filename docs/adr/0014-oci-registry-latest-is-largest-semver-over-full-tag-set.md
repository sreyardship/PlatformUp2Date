# The oci-registry latest source scans the full tag set, capped, not a recent window

The `oci-registry` latest source selects the **largest clean semver** among a
repository's tags — the same largest-semver rule `github-release` applies
([ADR-0010](0010-github-release-latest-is-largest-semver.md)). But the two cannot
share the *windowing* strategy. GitHub's `/releases` is **time-ordered**, so
ADR-0010 safely reads only the most-recent `page-size` releases. OCI `tags/list`
is, at best, **lexically sorted** (the spec says tags *should* sort lexically,
and lexical ≠ semver: `"1.10"` sorts *before* `"1.9"`), so the largest semver can
sit on any page. Correctness therefore forces scanning **every** tag and taking
the maximum — we cannot stop early on order. To bound the work this introduces a
per-app `max-tags` cap (default **1000**); the per-request page size reuses the
existing `page-size` leaf as the `n` query parameter (default **100**), and
pagination follows `Link: …; rel="next"` to completion or until the cap is hit.

## Considered Options

- **Window like `github-release`** (read only the first `page-size` tags) —
  rejected: with no version ordering, a first-page window routinely *misses* the
  largest semver on a later page.
- **Fail the app's scrape when the cap is exceeded** — considered, and the safer
  choice: a repo with more tags than `max-tags` (a `next` link still present at the
  cap) shows as `failed`, prompting the operator to raise the cap. Rejected *for
  now* in favour of truncate-with-warning (below), with the explicit option to
  revert if the false-green proves painful in use.
- **No cap, page to exhaustion** — rejected: a pathological repo (tens of
  thousands of tags) would issue many requests per scrape; `max-tags` is a safety
  bound.

## Consequences

- On hitting `max-tags` with more pages remaining, the source **takes the largest
  semver among the tags seen and logs a warning** naming the app/repo/cap. This is
  a deliberate, eyes-open compromise: because tags are not semver-ordered, a
  truncated scan can report a latest that is too *low*, which renders as a **false
  green** (app looks up-to-date while a higher tag exists past the cap) — *not* a
  visible anomaly. The warning is the only breadcrumb; raising `max-tags` or
  switching to fail-on-exceed are the levers if it bites.
- Only **clean** semver counts: tags with a prerelease segment (`1.22.0-alpine`,
  `1.22.0-rc1`) are skipped by default, because precedence would let a *variant*
  tag (`1.22.0-alpine`) outrank a real release (`1.21.0`) and be reported as
  latest. An optional per-app **`prerelease-filter`** string opts a single flavour
  back in by **exact** match on the tag's prerelease segment (e.g.
  `prerelease-filter: alpine` considers only `…-alpine` tags and reports the full
  tag, which compares apples-to-apples with an `…-alpine` current tag).
- The full-tag report assumes the *current* source also carries the flavour
  suffix (e.g. a `k8s-image` source reading the raw deployed `1.23.0-alpine`).
  When the *current* source instead reports a **clean** core — the common case
  for an `http` endpoint, since an app rarely knows at build time which image
  variant it will ship in — the full tag would render a false *outdated* (semver
  precedence makes `1.23.0-alpine` rank below `1.23.0`). For that case the
  existing per-app **`strip-prerelease`** flag is now also read by the
  `oci-registry` latest source: selection still **ranks by the full tag**
  (`1.24.0-alpine` beats `1.22.0-alpine`) but the **reported** value is the
  stripped core (`1.24.0`), so it compares apples-to-apples with a clean current.
  The same `strip-prerelease` flag was extended to the `k8s-image` current source
  (the `http` current source already had it) so either side can be normalised to
  clean cores; the two sides must agree (both stripped, or both full).
- An image published *only* under prerelease/variant tags yields "no usable tag"
  and fails that app — accepted as an edge case, revisited if a real target needs
  it.
