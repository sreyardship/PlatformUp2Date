# The github-release latest source picks the largest semver, not GitHub's `/releases/latest`

The `github-release` latest source called `GET /releases/latest`, which GitHub
defines as *"the most recent non-prerelease, non-draft release, sorted by
`created_at`"* — i.e. ordered by **publish time, not version**. For multi-branch
projects this returns the wrong release: `kubernetes/kubernetes` backports patches
across several active minors, so a later-published `v1.30.9` shadows the higher
`v1.31.2`, making a current cluster look *ahead of* latest and flapping Drift as
backports land. We therefore change `github-release` (for **all** apps) to list
`GET /releases` and select the **maximum semver** among releases where
`prerelease == false && draft == false`, comparing on **`tag_name`**.

## Considered Options

- **Keep `/releases/latest`** — rejected: the `created_at` ordering is wrong for
  any project maintaining multiple release branches.
- **Select on the release `name`** — rejected: `kubernetes/kubernetes` release
  `name` is often empty or `"Kubernetes v1.31.2"` (unparseable); `tag_name` is
  reliably the clean tag.
- **Page through `/releases` until provably exhausted** — rejected: `/releases`
  is `created_at`-ordered, not version-ordered, so "no higher version remains" is
  undecidable without reading everything — unbounded GitHub API cost against the
  scrape budget ([ADR-0006](0006-targeted-scrape-merges-without-touching-the-fleet-clock.md)).

## Consequences

- Page size is configurable per app via **`page-size`** (default **30**, valid
  range **1–100**, GitHub's `per_page` cap); an out-of-range value fails the boot,
  consistent with the latest source's existing fail-fast `create()` validation.
- We **assume the largest version is within the most-recent `page-size` releases**.
  Safe for any actively-released project; the failure mode (latest reads *low* →
  visible under-drift) is benign, not a crash.
- `kubernetes/kubernetes` is release-dense (multiple minors + alpha/beta/rc), so
  its app config sets `page-size: 100`.
- All four existing app URLs migrate `…/releases/latest` → `…/releases`. A stale
  `/releases/latest` URL returns a single object that fails to parse as an array,
  surfacing as a clear per-app failure that points at the fix.
