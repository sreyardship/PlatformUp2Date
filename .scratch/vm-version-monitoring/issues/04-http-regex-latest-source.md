# [04] http-regex latest source

Status: done
Type: AFK

## Plan
See `../plan.md`. Settles [ADR-0017](../../../docs/adr/0017-http-regex-latest-source.md).

## What to build
A generic latest source for upstreams without a release API: fetch a URL, regex out
version tokens, and pick the largest — covering Ubuntu (`meta-release-lts`) and
OpenWRT (the releases directory listing) with one adapter.

- `http-regex` source + factory (`type() = "http-regex"`) using a Quarkus REST client
  that fetches the `url` body as text (content-type agnostic — the Ubuntu feed is
  plain text, the OpenWRT page is HTML).
- Apply the configured `regex` (capture group 1) to the body, parse **every** match
  via the app `VersionParser`, and return the largest. The "largest" therefore honors
  the app's scheme — a calver app picks the largest calendar version.
- Factory validates a non-blank `url` and a compilable `regex` with at least one
  capture group.
- A body with no match (or only unparseable matches) throws, isolated as a failed app.
- Unauthenticated over the public CA (parity with `github-release`).

The Ubuntu LTS/interim "train" is handled by pointing at `meta-release-lts` — a config
choice, not logic in this source.

## Architectural surface
- Use cases: none new.
- Ports: `LatestVersionSource` (new adapter), `LatestVersionSourceFactory` (new kind).
- Adapters: `HttpRegexLatestSource`, `HttpRegexLatestSourceFactory`;
  `ApplicationConfigLoader` gains a `regex` field on `VersionSource`.

## Acceptance criteria
- [ ] An `http-regex` app fetches its `url`, applies `regex`, and returns the largest
      parsed match.
- [ ] Largest-pick honors the app scheme (a calver app orders calendar versions).
- [ ] Factory rejects a blank `url` or a regex that doesn't compile / has no capture
      group.
- [ ] A no-match / all-unparseable body fails that app's scrape, isolated.
- [ ] Integration tests against WireMock: a `meta-release-lts` fixture → latest LTS;
      an OpenWRT releases-listing fixture → largest version.

## Blocked by
`01-versionvalue-and-version-scheme.md`. (Calver targets also need `02` for correct
largest-pick ordering.)
