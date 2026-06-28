---
status: proposed
---

# Upstream versions without a release API are read by a generic http-regex source

Neither Ubuntu nor OpenWRT publishes GitHub releases, so the `github-release`
latest source does not reach them. Their upstream version *is* available, just not
as a clean API: Ubuntu exposes a flat text feed at
`https://changelogs.ubuntu.com/meta-release-lts` (RFC822-ish `Version:` stanzas),
and OpenWRT an HTML directory listing at `https://downloads.openwrt.org/releases/`.
Both collapse to "fetch a URL, pull version tokens out with a regex, pick the
largest" — so we add **one generic `http-regex` latest source** rather than a
per-distro adapter each.

```yaml
# Ubuntu — point at the LTS feed
latest:
  type: http-regex
  url: https://changelogs.ubuntu.com/meta-release-lts
  regex: 'Version:\s*(\d+\.\d+(?:\.\d+)?)'
# OpenWRT — the releases directory listing
latest:
  type: http-regex
  url: https://downloads.openwrt.org/releases/
  regex: 'href="(\d+\.\d+\.\d+)/"'
```

The decision rests on:

**Generic over distro-specific.** Two bespoke parsers (`ubuntu-meta-release`,
`openwrt-releases`) would each be standing contextual weight for what is, in both
cases, "regex the body and take the max." A single config-driven `url` + `regex`
covers both and many vendor download pages besides — it is the latest-side mirror
of the "largest version wins" rule the GitHub source already follows
([ADR-0010](0010-github-release-latest-is-largest-semver.md)). The largest match
is selected under the Application's Version scheme, so calendar versions order
correctly without special-casing.

**Ubuntu's LTS/interim train is solved by the URL, not by code.** A box on
`22.04 LTS` is not "behind" `24.10`; LTS and interim are different release trains
and comparing across them is wrong, not just imprecise. Pointing at
`meta-release-lts` (instead of `meta-release`) means the feed *only* contains LTS
releases, so "largest" is automatically "latest LTS" and an LTS box compares
LTS-to-LTS. The train awareness lives in one URL choice — no `Supported:`-flag
filtering, no train logic in the source.

**`http-regex`, not `html-regex`.** The architecture sketch called this
`html-regex`, but `meta-release-lts` is plain text, not HTML. The source treats
the body as text and is agnostic to content type, so the honest name describes the
transport (`http`) and the operation (`regex`), not an assumed body format.

## Considered Options

- **Distro-specific `ubuntu-meta-release` + `openwrt-releases` adapters** —
  rejected: buys precise stanza/listing parsing (e.g. honouring `Supported:`
  directly) at the cost of two bespoke parsers, when a URL choice plus a regex
  reaches the same answer. Not worth the standing contextual load.
- **Parse Ubuntu's `Supported:` flag to find the current release** — rejected as
  unnecessary: selecting the `meta-release-lts` feed obviates it entirely.
- **Name it `html-regex`** — rejected: would lie about the body for the plain-text
  Ubuntu feed (same honesty rule applied to the `calver`/`coerce` and `ssh`
  naming elsewhere in this work).

## Consequences

- The largest-match selection honours the per-app Version scheme
  ([ADR-0015](0015-calver-as-a-first-class-version-scheme.md)), so a `calver` app
  picks the largest calendar version, not the largest semver.
- The regex is the operator's responsibility: it must be anchored tightly enough
  (e.g. to the link pattern or the `Version:` key) that stray numbers on the page
  are not captured. A regex that matches nothing fails that app's scrape, isolated
  as usual.
- A future self-hosted or authenticated upstream behind a private CA would need
  the transport hardening (`ca-cert`, file token) that the `http` *current* source
  already grew in [ADR-0012](0012-http-current-source-file-token-and-custom-ca.md);
  `http-regex` starts unauthenticated over the public CA, like `github-release`.
