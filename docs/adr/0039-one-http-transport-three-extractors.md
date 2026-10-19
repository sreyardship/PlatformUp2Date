---
status: accepted
---

# One HTTP transport, three extractors: the current-leg HTTP kinds share a deep module

> **Amended by [ADR-0040](0040-latest-leg-on-the-shared-http-transport.md):** the option
> "fold `github-release` and `http-regex` in now", deferred below, has since been taken, and
> `oci-registry` with it. `HttpTransport` now serves both legs, so its javadoc and
> `HttpTransportConfig`'s messages name the leg; `HttpGetResponse` carries the final hop's URI.
> The decision itself — one transport, per-kind extractors — is unchanged.

`http-json`, `http-header` and `http-prometheus` all make the same configured GET. Each
kind had its own copy of it: a fetch interface (`HttpJsonCurrentVersionClient`,
`HttpHeaderFetch` + `HttpHeaderResponse`, `PrometheusBodyFetch`), a transport class behind
it (`RedirectFollowing…`), and one test fake. Nothing else ever sat behind any of the three
interfaces. The three returned different things — a `JsonNode`, a status plus headers, a
body — so the "which status is OK" rule lived in the transport for two kinds and in the
source for the third, and every transport change (ADR-0029 redirect rules, credential
stripping, trust store, auth rendering) was made and tested three times.

The current leg now has **one transport and three extractors**.

- **`HttpTransport`** (in `adapters/out/versionsource/http/`) does the configured GET and
  returns the final `HttpGetResponse`: status, raw headers and body of the last hop.
  Redirects, the ADR-0029 credential-origin and downgrade rules, per-app TLS trust and
  `Authorization` rendering all live inside it. It is built once per app from the
  `HttpTransportConfig.Resolution` the factory already had, via `HttpTransport.from`.
  It takes no position on which status is acceptable.
- **`HttpVersionExtractor`** is each kind's rule over that response, and the status check
  moves here, where the per-kind decision belongs: `HttpJsonVersionExtractor` gates on 2xx
  then follows a JSON Pointer; `HttpHeaderVersionExtractor` reads its header off any status
  (ADR-0030); `HttpPrometheusVersionExtractor` gates on 2xx then parses the exposition
  (ADR-0033). Extractors are pure, so each kind's tests feed it a hand-built response.
- **`HttpCurrentSource`** is the one `CurrentVersionSource` all three kinds are: a transport
  and an extractor. Each factory is now "validate the fragment, build the shared transport,
  pick the extractor".

The three fetch interfaces and the three transport classes are deleted.

## What stays the same

Config kinds, `type:` strings and the per-kind CDI factories (ADR-0005, ADR-0031). The
behaviour operators see: same requests, same redirect and credential rules, same status
handling per kind, same failure messages. `HttpTransportConfig`, `RedirectFollowingHttpGet`
and `AuthorizationHeaderRenderer` keep their behaviour; only their javadoc moves on.

## Tests

Redirects, TLS and auth rendering are proven once, against real servers, in
`HttpTransportIT`. Each kind keeps a unit suite for its extractor over fake responses and a
thin integration suite proving its own rule end to end through its factory. The
per-kind redirect and TLS suites, and the `http-json` client-factory suite, are gone.

## Considered Options

- **One shared fetch interface instead of three** — rejected. It would still have exactly
  one production implementation and one test fake; the interface adds nothing a final class
  and a pure extractor do not. The seam worth having is the extractor, which genuinely has
  three implementations.
- **Keep three source classes, each holding the transport** — rejected. They would differ
  only in extraction, which is what the extractor is; a separate source per kind is the
  duplication this ADR removes, one layer up.
- **Let the transport enforce 2xx and make `http-header` opt out** — rejected. Two of three
  kinds gating on 2xx is not a transport default; it is two extractor decisions that happen
  to agree. A default-on gate with an opt-out would put ADR-0030's defining behaviour behind
  a flag.
- **Fold `github-release` and `http-regex` in now** — deferred. Both make their own GET on
  the latest leg; moving them onto `HttpTransport` is a separate change, and gives
  `http-regex` the `auth:` and `ca-cert` support it lacks. ADR-0033 rejected a shared seam
  with `http-regex` on the grounds that compliance already lived inside
  `RedirectFollowingHttpGet`; that remains true, and this ADR collapses the three copies of
  the wiring around it rather than the GET itself.

## Consequences

- A transport change is made once and tested once.
- A new HTTP current kind is one extractor, one factory and no transport.
- `HttpCurrentSource` is not `Closeable`; the transport holds no resource needing release,
  matching what `http-header` and `http-prometheus` already did.
