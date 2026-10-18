---
status: accepted
---

# The latest leg fetches through the shared HTTP transport too

[ADR-0039](0039-one-http-transport-three-extractors.md) gave the current leg one deep HTTP
transport (`HttpTransport` returning an `HttpGetResponse`) with one extractor per kind, and
deferred the latest leg. The three latest kinds each still owned their own GET: `github-release`
through a `GithubReleaseClient` JAX-RS interface kept alive as a functional shim, a
`RedirectFollowingGithubReleaseClient` that must not implement it, and a `VersionResponseExceptionMapper`
fed a synthetic JAX-RS `Response`; `http-regex` through its own `RedirectFollowingHttpGet` with
an inline status gate and no `auth` or `ca-cert`; `oci-registry` through a third
`RedirectFollowingHttpGet` behind a private JAX-RS `Response` adapter, its own `truncate`, and
the only connection-failure retry in the codebase. Three constructions of the GET, two renderings
of `Authorization`, three status gates, no TLS configuration on the latest leg, and some 2,500
lines of integration tests re-proving redirect and credential rules `HttpTransportIT` proves once.

Every HTTP kind on either leg now fetches through `HttpTransport`.

- **`github-release` and `http-regex` are extractors.** `GithubReleaseVersionExtractor` gates
  on 2xx, decodes the release array and selects the largest non-prerelease, non-draft
  `tag_name` (ADR-0010). `HttpRegexVersionExtractor` gates on 2xx and applies the shared
  `RegexVersionExtractor`'s largest-wins rule (ADR-0017). `HttpLatestSource` pairs a transport
  with an extractor, the latest-leg twin of `HttpCurrentSource`. Each factory is now "validate
  the fragment, resolve the transport inputs, build the shared transport, pick the extractor".
- **`oci-registry` is a loop over `HttpGetResponse`.** The bearer dance (ADR-0013) and the
  pagination (ADR-0014) stay as they were; only the GET underneath changed. They need GETs at
  URIs learned from earlier responses — the token realm from a `401`, the next page from a
  `Link` — each with its own credential, so `HttpTransport` gains
  `get(URI, Optional<ClientRequestFilter>)`: a further GET over the same transport (same TLS
  trust, same redirect and credential rules) at a learned URI with an explicit credential.
  `get()` stays the configured GET. The oci transport is bound to the first `tags/list` URL and
  carries no configured credential: the `basic` credential goes to the realm, the minted
  `Bearer` to the tags pages.
- **`HttpGetResponse` carries the final hop's URI.** A relative `Link` target is resolved
  against the URI of the response that carried it (RFC 8288), which after a redirect is the
  canonical endpoint, not the configured one. It also offers `truncatedBody()`, the bounded body
  copy a failure message quotes, replacing three private copies of the same method.
- **`HttpTransportConfig` names the leg.** It is built by `forCurrentSource(kind)` or
  `forLatestSource(kind)`, so a `FailedLatestSource` reads "The 'http-regex' latest source's
  'ca-cert' …" and the current-leg wording is byte-for-byte what it was.
- **The connection-failure retry moves into the transport.** `oci-registry` alone retried a GET
  whose connection dropped or reset before an answer arrived — the HTTP/1.1 keep-alive close
  race, which RFC 9112 §9.3.1 says a client should retry for an idempotent request. The race is
  not a registry trait, and every GET the transport makes is idempotent, so
  `RedirectFollowingHttpGet` now re-sends one such hop exactly once, for every kind. An HTTP
  error status is an answer and is never retried; nor is a failure a second attempt cannot
  change — a TLS handshake the configured trust refuses, an unresolvable host, a timeout.

Deleted: `GithubReleaseClient`, `RedirectFollowingGithubReleaseClient`,
`GithubReleaseLatestSource`, `HttpRegexLatestSource`, `VersionResponseExceptionMapper`, oci's
JAX-RS `Response` adapter, `truncate` and `retryOnConnectionFailure`, the no-argument
`RedirectFollowingHttpGet` constructor and the `basicHeaderValue`/`bearerHeaderValue` statics
whose only callers were the deleted transports. `RedirectFollowingHttpGet` is constructed in
exactly one place, inside `HttpTransport`.

## What the latest kinds gain

All three read `ca-cert` and `insecure-skip-tls-verify` through the shared config: a private
Harbor or a vendor listing behind a company CA is configured like an `http-json` current source.

- `http-regex` reads `auth` (`basic` / `bearer` / `bearer` from `token-file`). ADR-0033
  rejected a shared seam with `http-regex` because it would "grant `http-regex` a hardening it
  does not offer"; this ADR grants that hardening on purpose. The ADR-0033 ground that the
  ADR-0029 compliance already lived inside `RedirectFollowingHttpGet` remains true: what
  collapses here is the wiring around the GET, not the GET.
- `github-release` reads `auth` too. An app's own `auth` block wins over the fleet-wide
  `github.token`; with no `auth` block the fleet-wide token is sent as before, blank meaning none.
- `oci-registry` keeps its `basic`-only `auth` rule (ADR-0013): the factory validates it itself
  and hands the credential to the source for the realm, never to the transport as its
  configured credential. `HttpTransportConfig` resolves only the TLS part for this kind.

## What stays the same

Config kinds, `type:` strings, per-kind CDI factories (ADR-0005, ADR-0031), requests on the wire,
redirect and credential rules (ADR-0029), per-kind status handling, selection rules (ADR-0010,
ADR-0014, ADR-0017) and the dance (ADR-0013).

Wording that did move, named so it is not rediscovered from the diff: a `github-release` or
`http-regex` read that fails at the transport (too many redirects, refused downgrade, a
connection failure after the retry) now surfaces the transport's own exception rather than an
`http-regex`-only `VersionFetchException` wrapper, so the message loses its "Failed to fetch
'…': " prefix; `http-regex`'s non-2xx message now names the status as well as the URI; messages
that name where a body was read from name the final hop's URI. `oci-registry` follows a
`Link: rel="next"` whenever one is present rather than only when its target carries `last=`,
which is what the Distribution Spec asks of a client and what every registry sends anyway.

## Tests

Redirects, TLS, auth rendering, the learned-URI GET and the retry are proven once, against real
servers, in `HttpTransportIT`. `GithubReleaseVersionExtractorTests` and
`HttpRegexVersionExtractorTests` feed each extractor hand-built responses, as the current-leg
extractor suites do. Each latest kind keeps a thin integration suite built end to end through
its factory from a config fragment: `github-release` proves its URL, deserialisation and which
credential reaches GitHub; `http-regex` proves the two ADR-0017 fixtures and one hardened
fragment — `auth` plus `ca-cert` reaching a TLS endpoint behind a private CA; `oci-registry`
keeps one suite per rule (direct read, dance, pagination, prerelease filter). The redirect,
cross-origin, downgrade and retry cases in the per-kind suites are removed, not moved. The
`http-regex` and `oci-registry` suites no longer need a Quarkus context; `github-release` keeps
one for its CDI-discovery case only.

## Considered Options

- **A URL-free transport, with the URI passed on every `get`** — rejected. ADR-0039 settled on
  one transport bound to the app's `url`, and five of six kinds fetch exactly that URL. One
  overload for the kind that learns its URIs is smaller than re-threading a URI through every
  source.
- **`oci-registry` building an `HttpTransport` per call** — rejected: an `HttpClient` and an
  `SSLContext` per GET, three to five times per scrape, for no gain over one transport per app.
- **Keeping the connection-failure retry an `oci-registry`-only rule** — rejected. Nothing about
  the keep-alive close race is specific to registries; GitHub and a vendor's web server close
  idle connections the same way. Keeping it in one kind would be the duplication this ADR
  removes, waiting to happen the first time another kind hits it.
- **Refusing a per-app `auth` on `github-release` while `github.token` is set** — rejected. An
  operator who writes an `auth` block on one app means it for that app; silently preferring the
  fleet-wide token, or refusing, both surprise more than "the app's own block wins".
- **Letting `HttpTransportConfig` resolve `oci-registry`'s `auth`** — rejected: it accepts
  `bearer`, which ADR-0013 refuses for this kind, and a resolved filter would become the
  transport's configured credential, sent to the resource rather than the realm.

## Consequences

- A transport change is made once and tested once, for six kinds on two legs.
- A new HTTP kind on either leg is one extractor, one factory and no transport.
- `HttpLatestSource` and `OciRegistryLatestSource` are not `Closeable`; the transport holds no
  resource needing release, matching `HttpCurrentSource`.
- `docs/configuration.md` lists `auth`, `ca-cert` and `insecure-skip-tls-verify` for the latest
  kinds, with `oci-registry`'s `basic`-only exception, and the sample
  `deploy/k8s/base/platform-config.yaml` shows them commented out.
- The `@RegisterForReflection` on `VersionResponseExceptionMapper` is gone with the class.
  `GithubReleaseResponseDTO` gains one: as a REST-client return type it was registered
  implicitly, and with the client gone it is deserialized by a plain `ObjectMapper`, exactly
  `TagsListDTO`'s situation (ADR-0025 is the precedent for this class of regression). The native
  integration test caught the omission on the first CI run and still proves a `github-release`
  read behind a redirect end to end through the built artifact.
