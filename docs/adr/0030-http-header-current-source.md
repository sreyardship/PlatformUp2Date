# A current version carried in a response header is read by an http-header source, which ignores the status code

> **Amended by [ADR-0032](0032-config-errors-degrade-per-app-never-the-boot.md):** `version-header` no longer fails the boot. This ADR followed the then-current `url`/`regex` precedent rather than relitigating it; that precedent is now reversed, so a missing or blank `version-header` degrades the `current` side with a clear reason, as `url` does.

Jenkins does not publish its version in any response body. It publishes it in a response
header — `X-Jenkins` — on its top page and on every `.../api/*` page
([docs](https://www.jenkins.io/doc/book/using/remote-access-api/)). None of the existing
source kinds can reach that: `http` extracts a JSON Pointer from a body it deserialises as
JSON (ADR-0007), and `http-regex` (ADR-0017) is a *latest*-leg source that pattern-matches a
body as text. We therefore add a fourth HTTP-shaped kind, **`http-header`**, a `current`-leg
source that reads a named response header and never looks at the body.

```yaml
current:
  type: http-header
  url: https://jenkins.example.com/
  version-header: X-Jenkins       # required
  # regex: 'nginx/(\d+\.\d+\.\d+)'  # optional; absent = the raw trimmed header value
```

The decision rests on four points, the first of which is the one a future reader will trip
over.

## The status code is ignored; only the header's presence matters

Every other HTTP-fetching source refuses a non-2xx final response — the `http` current leg via
`VersionResponseExceptionMapper` in `RedirectFollowingHttpCurrentVersionTransport`, and
`http-regex` in its own `fetchBody()`. Both are right to: they parse the body, and a 403's body
is an error page, not data.

`http-header` deliberately does not. It reads the header off the final response **whatever the
status code was**, and consults the status only when composing a failure message.

This is not a hypothetical convenience. A secured Jenkins refuses the anonymous top page and
still volunteers its version:

```
$ curl -sI https://ci.jenkins.io/
HTTP/2 403
server: Jetty(12.1.8)
x-jenkins: 2.568.2
x-hudson: 1.395
```

Every real Jenkins is a secured Jenkins. Inheriting the 2xx gate would mean the motivating app
fails every scrape while the answer sits in a response we already hold in memory. The
justification generalises beyond Jenkins: a response header of this kind is metadata the server
volunteers **about itself**, and is independent of whether it authorises the caller to read the
*resource*. A 403 is an authorization fact about the body. It says nothing about what version
the responding instance is.

The cost is that a genuinely broken endpoint — a reverse proxy returning 502 for a dead
backend, say — is not distinguished by status. In practice such a response carries no
`version-header` either, so the read fails anyway; it fails with "no `X-Jenkins` header on the
502 response from …" rather than "non-success status", which is at least as diagnostic.

A future reader must not "fix" this into consistency with the body-parsing sources. The
inconsistency is the feature.

## The first match, not the largest

`http-regex` takes the **largest** match in the body, the same largest-wins selection
`github-release` (ADR-0010) and `oci-registry` (ADR-0014) apply. That rule is correct on the
*latest* leg, where "newest release upstream" genuinely means a maximum over candidates.

`http-header` is a `current`-leg source, where largest-wins is not merely imprecise but wrong.
A current version is a single *observation*, not a selection. With a slightly loose pattern
against `Server: nginx/1.25.3 (Ubuntu/22.04)`, largest-wins reports the application's current
version as **22.04** — silently, with no error, as a confidently green board. So the optional
`regex` takes capture group 1 of the **first** match only.

The shared machinery is extracted rather than duplicated: pattern compilation, boot-time
validation (compiles, at least one capture group), group-1 extraction and tolerance of
unparseable candidates are common, and only the selection rule differs — `largestIn` for
`http-regex`, `firstIn` for `http-header`.

## The header name is matched case-insensitively

ADR-0007 made `version-key` deliberately case-**sensitive**, dropping the previous
`Version`/`version` tolerance. Matching header names case-insensitively looks like a
contradiction of that and is not: JSON object keys are case-sensitive by specification, and
HTTP field names are case-insensitive by specification (RFC 9110 §5.1). The consistent rule is
*follow the substrate's own spec*, and it yields opposite answers for the two. So
`version-header: X-Jenkins` matches the `x-jenkins` the wire actually carries.

A repeated header takes the first value — consistent with the first-match rule above. This does
*not* follow ADR-0012's refusal to rank `token` against `token-file`, and the distinguishing
principle is **who can fix it**: `token`/`token-file` is config ambiguity the operator caused
and can resolve, so refusing hands them an actionable error, whereas a repeated response header
is upstream ambiguity the operator cannot change, so failing would give them a red board and no
remedy.

The three runtime failures are reported distinctly, because they are different facts and lead
to different fixes: the header being **absent**, the header being **present but empty**, and
the value being **present but unparseable** (or matching no configured regex) each carry their
own message, the first two naming the status code observed.

## Full transport parity, obtained by extraction

ADR-0017 gave `http-regex` no hardening at all — "unauthenticated over the public CA, like
`github-release`". `http-header` instead gets full parity with the `http` current source:
`auth` (basic / bearer / bearer-from-file), `ca-cert`, `insecure-skip-tls-verify`, and
ADR-0029's redirect rules. Jenkins needs none of it, so this is not need-driven — it is
cost-driven. All of that already lives one layer below the JSON transport, in
`RedirectFollowingHttpGet`: `withTls(Optional<KeyStore>, boolean)` covers the TLS axes,
`get(URI, Map<String,String>)` returns an `HttpResponse<String>` that already exposes both
`statusCode()` and `headers()`, and the origin-bound credential and downgrade rules are
enforced inside it.

So `http-header` needs no new transport whatsoever. What it needs is the config-to-transport
wiring that `HttpCurrentSourceFactory` currently owns privately — building the auth filter from
the `auth` fragment and rendering it to an `Authorization` value, building the truststore from
`ca-cert`, and enforcing the `ca-cert` × `insecure-skip-tls-verify` mutual exclusion. That is
extracted into a collaborator shared by both current-leg factories, preserving ADR-0008's and
ADR-0012's value-error-to-`FailedCurrentSource` semantics unchanged.

Copying it instead would put ADR-0029 compliance in two places and guarantee they drift.
The extraction is behaviour-preserving for `http` and must land as its own step, before the new
kind is added.

## Scope

`http-header` is a `CurrentVersionSourceFactory` only. A response header describes the
*responding instance*, which is inherently a current-side fact; no upstream publishes its
newest release in a header. Reading versions off headers on the `latest` leg is left to the
config-shape rework in #51, which reorganises the leg axis anyway.

Configuration errors follow the existing split without relitigating it: an absent or blank
`version-header`, and a `regex` that does not compile or has no capture group, fail the boot
(matching `url`, `repo` and `http-regex`'s own `regex`); `auth` and `ca-cert` value problems
degrade the single app to a `FailedCurrentSource`. That split is itself questionable and is
tracked separately in #52, but this kind follows precedent rather
than inventing a fifth rule.

`strip-prerelease` is honoured, as it is for `http`, `k8s-image` and `oci-registry`.

## Considered Options

- **A `version-header` field on the existing `http` kind** — rejected. The `http` transport
  decodes the final body as JSON unconditionally and `HttpCurrentVersionClient` is typed
  `JsonNode`; Jenkins' top page is HTML, so the decode throws before any extraction could
  happen. Adding the field would force the body decode to become conditional and
  `getCurrentVersion()` to stop meaning "the JSON body", i.e. surgery on the source every other
  app already depends on, plus a mutual-exclusion matrix against `version-key`.
- **`HEAD` instead of `GET`** — rejected. It saves buffering a body we discard, but
  `RedirectFollowingHttpGet` is GET-only and is where the ADR-0029 rules live, so HEAD means
  either widening that class or growing a second one that has to re-earn its guarantees; HEAD is
  unevenly implemented behind reverse proxies; and the Jenkins documentation specifies loading a
  page. One discarded page per scrape interval is not a cost worth that.
- **Requiring a 2xx like every other HTTP source** — rejected; see above. It fails on precisely
  the case the kind exists for.
- **Reusing `http-regex`'s largest-wins selection** — rejected: silently misreports a current
  version when a pattern matches more than once.
- **Naming it `http-jenkins`, or parsing `X-Jenkins` by default** — rejected on the same honesty
  grounds as `http-regex` over `html-regex` (ADR-0017): the mechanism is "read a named response
  header", and nothing about it is Jenkins-specific.
- **Starting bare like `http-regex`, with no auth or TLS options** — rejected: parity is nearly
  free given where the hardening already lives, and the extraction it forces is wanted by #51
  regardless.

## Consequences

- `HttpCurrentSourceFactory` is refactored (behaviour-preserving) before this kind is added, so
  the shared transport wiring has exactly one home.
- The `http` kind's name grows more misleading with a third HTTP sibling: it is really
  "http, parsed as JSON". Renaming it to `http-json` and collapsing all three behind one `http`
  kind with a `parse` sub-option is tracked in #51 and deliberately deferred until all three
  parsing strategies exist. [ADR-0031](0031-http-current-source-renamed-http-json.md) answers
  this: it takes the rename (`http` → `http-json`) but declines the `parse`-sub-option
  collapse, so the three kinds stay separate factories rather than merging into one.
- Jenkins' `latest` leg needs no new work: `jenkinsci/jenkins` release *tags* are
  `jenkins-2.579`, which will not parse under ADR-0010's largest-semver rule, but
  `type: http-regex` against `https://updates.jenkins.io/stable/latestCore.txt` (which redirects
  to the current stable line and returns a bare `2.568.2`) reads the LTS release directly.
- Jenkins LTS versions (`2.568.2`) are valid semver; weekly versions (`2.579`) have two
  components and are rejected by the strict semver parser. Monitoring a weekly-channel Jenkins
  would need a version-scheme change, not a source change, and is out of scope here.
