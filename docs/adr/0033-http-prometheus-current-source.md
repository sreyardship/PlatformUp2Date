---
status: accepted
---

# A current version published as a metric label is read by an http-prometheus source, which takes the first matching sample

Blackbox exporter publishes its version nowhere in a response body and nowhere in a response
header. It publishes it the way the whole Prometheus ecosystem does — as a **label on a
constant-1 `*_build_info` gauge** on its metrics endpoint:

```
# HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch...
# TYPE blackbox_exporter_build_info gauge
blackbox_exporter_build_info{branch="HEAD",goversion="go1.22.4",revision="0ec2a6b",version="0.25.0"} 1
```

None of the existing kinds reaches that. `http-json` ([ADR-0007](0007-json-pointer-current-version-extraction.md))
deserialises the body as JSON and a metrics body is not JSON. `http-header`
([ADR-0030](0030-http-header-current-source.md)) reads response headers and the version is not in
one. `http-regex` ([ADR-0017](0017-http-regex-latest-source.md)) is a *latest*-leg source that
pattern-matches text.

We therefore add a fifth kind, **`http-prometheus`**, a `current`-leg source that parses a
Prometheus text-exposition body and reads the version from a named label on a named metric.

```yaml
current:
  type: http-prometheus
  url: https://blackbox.example.com/metrics
  metric: blackbox_exporter_build_info   # required
  labels:                                # optional; selects among installations
    job: blackbox
  # version-label: version               # optional; defaults to `version`
  # regex: 'v(\d+\.\d+\.\d+)'            # optional; absent = the raw label value
```

The convention this targets is not blackbox-specific and not even mostly blackbox: node_exporter,
Prometheus itself, Alertmanager, Grafana, Loki, Argo CD and most of the Go ecosystem publish
`*_build_info{version="…"}`. One kind reaches all of them.

## The version is a label, never the sample value

The kind reads a **label value**. The numeric sample value is read past and discarded, and is
deliberately unreachable from configuration — there is no `version-from: label|value` switch.

This is not a limitation to be lifted later. The `build_info` convention exists precisely because
a Prometheus sample value is a float and a version is not: `0.25.0` cannot be a sample value at
all, and a version that *can* be one is degenerate (`1.25` — is that `1.25.0` or `1.2.5`?). A
`version-from` axis would be a second code path with no user, purchased with a permanent config
question every operator has to answer.

## The name states the format, following ADR-0031

[ADR-0031](0031-http-current-source-renamed-http-json.md) made it a rule that an HTTP source
kind's name says where in the response it finds the version. Two shapes satisfy it: `http-header`
and `http-regex` name the extraction *mechanism*, while `http-json` names the **body format**, with
a second field (`version-key`) naming the location inside it.

This kind is structurally a twin of `http-json` — a specified wire format, parsed, with `metric` +
`version-label` naming the location — so it is named after the format. "Prometheus" here is not a
vendor borrowed onto a generic mechanism, the way `http-jenkins` would have been in ADR-0030; it is
the name of the text exposition format, as "JSON" is.

The parser silently accepts OpenMetrics as well. OpenMetrics is a near-superset, and a tolerant
line parser handles both without a mode flag: its `# EOF` terminator falls out of skipping comment
lines, and its exemplars live after the sample value, in the part this kind already discards. The
*name* still commits to Prometheus, because that is what every endpoint labels its output.

## The first matching sample wins; conflicts are not refused

An endpoint can carry several samples of one metric — a Pushgateway, a `/federate` output, or a
service mid-rollout behind a load balancer:

```
blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
```

The source takes the **first sample in document order** that matches the metric name and the
optional `labels:` selector. It does not compare the samples, and it does not refuse when they
disagree.

Refusing was seriously considered and rejected. The argument for it was ADR-0030's own reasoning
against largest-wins: a `current` version is a single observation, and silently picking one of two
genuinely different deployed versions is the "confidently green board" failure that ADR warned about, on an
ordering the exposition format does not guarantee is stable.

It was rejected because the premise is wrong about how often, and for how long, the case occurs.
Two samples disagreeing means a rollout is *in flight* — a condition that is short-lived by
construction and resolves itself without anyone acting on it. Flapping the board red for the
duration of a deploy reports a problem that is not one, trains operators to ignore the colour, and
is a worse artifact than briefly showing one of the two versions actually running. Monitoring
tolerates a transiently-stale reading; it does not tolerate noise.

This also keeps one rule across the whole `current` leg — first match wins, as in `http-header`'s
repeated header and `RegexVersionExtractor.firstIn` — rather than making this kind the exception.

## `labels:` selects an installation; it is not a conflict remedy

The optional `labels:` map is exact string equality on every entry, ANDed, with no `!=` / `=~` /
`!~`. It exists so an operator running **two installations of the same application** — reachable
through one aggregating endpoint, monitored as two Applications — can point each Application at its
own series. That is its motivation, and the reason the conflict rule above is safe rather than
negligent: the disagreement case is a rollout, and the multiple-installation case has its own
answer.

The restraint on matcher operators follows `oci-registry`'s `prerelease-filter`
([ADR-0014](0014-oci-registry-latest-is-largest-semver-over-full-tag-set.md)), which is
documented EXACT-match-only for the same reason: importing Prometheus's four matcher operators
means owning matcher-escaping semantics in a config field, and a selector that narrows to one
series never needs them.

`labels:` is the first non-scalar field in `VersionSource`, which binds through SmallRye
`@ConfigMapping`. [ADR-0032](0032-config-errors-degrade-per-app-never-the-boot.md) rests on the
config document binding as a whole, so the binding is proven by test before anything is built on
it. The failure mode to guard is not a boot break — arbitrary map keys cannot be invalid — but the
quiet one: the map binding empty, the selector being ignored, and the wrong installation being
monitored under a green board.

## Unlike `http-header`, this kind requires a 2xx

ADR-0030 made `http-header` deliberately status-blind and told a future reader not to "fix" it into
consistency. `http-prometheus` goes the other way, and the two are not in conflict.

ADR-0030's justification was narrow and specific: a response header is metadata the server
volunteers **about itself**, independent of whether it authorises the caller to read the
*resource* — so a 403 is an authorization fact about the body and says nothing about the version.
Here the metrics body **is** the resource. A 401 or 403 yields a login page containing no metrics;
a 502 yields a proxy error page. Under a status-blind rule those fail anyway, only with the less
diagnostic "metric `blackbox_exporter_build_info` was not present" instead of "non-success status
403". The gate costs nothing real and buys a better error, so it matches `http-json` and
`http-regex`.

## Failure messages never carry the body

`http-regex` gets away with loose diagnostics because upstream release pages are small. A
`/metrics` body is routinely hundreds of kilobytes, fetched every scrape, for every app, forever.
No failure message from this kind embeds the body or a slice of it — following `http-regex`'s own
gate, which names the URI and status only, and *not* the REST-client
`VersionResponseExceptionMapper`'s truncate-to-512 approach.

Four failures are reported distinctly, because they lead to four different fixes:

1. a non-2xx final response — status and URL
2. the metric name absent from the body
3. the metric present, but no sample matching `labels:` — naming a **bounded** number of the label
   sets actually seen for that metric, so the operator can correct the selector
4. a sample matched, but `version-label` is absent from it, or its value is empty after trimming,
   or the value does not parse (or matches no configured `regex`)

Only (3) quotes anything from the document, and only label sets of the named metric, capped.

## A hand-rolled parser, scoped to what the kind needs

Parsing lives in a small class in the kind's own package, pure and unit-testable, exactly as
`OsReleaseParser` sits beside `SshOsReleaseCurrentSource`. It takes body text, a metric name and a
label filter, and returns matching samples' label maps in document order. It does **not** parse
values, timestamps, types, histograms or summaries, because this kind never needs them.

The grammar it must handle is small: skip `#` comment lines (which covers `# HELP`, `# TYPE` and
OpenMetrics' `# EOF`); match the metric name **exactly**, up to `{` or whitespace, so `metric:
blackbox_exporter_build_info` does not match `blackbox_exporter_build_info_extra`; unescape the
three defined label-value sequences (`\\`, `\"`, `\n`); ignore everything after the closing `}`.

The escaping matters even though no real version label contains an escape, because the label set is
what `labels:` matches against — a naive split on `"` produces a wrong *neighbouring* label value
and therefore a wrong selector decision.

A library was rejected: the Java Prometheus client libraries are built to write exposition format,
not read it, and adding a dependency plus its GraalVM reflection surface for roughly a hundred lines
of line-splitting is a poor trade in a codebase that has already paid that cost once
([ADR-0025](0025-ssh-os-release-native-image-reachability.md)).

## Considered Options

- **A `version-from: label|value` switch** — rejected; see above. A second code path with no user,
  paid for with a permanent config question.
- **Widening `http-regex` to the `current` leg instead of a new kind** — rejected. ADR-0031 said
  widening a kind across legs is the answer when the substance already fits; it does not here.
  Regex-over-Prometheus makes every operator hand-write a fragile pattern against a format that has
  a specification, and it cannot support `labels:` matching, which needs real parsing.
- **Naming it `http-metric`** — rejected as exactly the dishonesty ADR-0031 exists to catch:
  "metric" names neither a format nor an extraction, and would not tell an operator whether a
  JMX or StatsD endpoint qualifies.
- **Naming it `prometheus-metric`** — rejected: breaks the `http-` sibling prefix ADR-0031 spent a
  breaking change to establish, for a kind that fetches over HTTP exactly like its siblings.
- **Naming it `http-openmetrics`** — rejected: almost nothing labels its output as OpenMetrics,
  blackbox included, so the name sends operators hunting a content type they will never see.
- **Refusing when matching samples disagree** — rejected; see above. It reports a rollout as a
  fault.
- **A full Prometheus matcher grammar for `labels:`** — rejected on `prerelease-filter`'s
  precedent; exact equality reaches every case the field exists for.
- **A `List<String>` of `NAME=VALUE` entries for `labels:`** — a worse ConfigMap surface than the
  map, and not taken. It is the **decided fallback** should the map prove unbindable in that
  position: `List<String>` binding is well-trodden and `NAME=VALUE` has no escaping hole. Dropping
  the selector was the alternative and was rejected — it would leave the two-installations case
  unserved and reopen the conflict rule above, which depends on that case having its own answer.
- **Extracting a shared `HttpBodyFetch` seam with `http-regex`** — rejected for now. ADR-0030
  justified the `HttpTransportConfig` extraction on ADR-0029 compliance drifting between two
  copies; that ground is absent here, since compliance lives inside `RedirectFollowingHttpGet`,
  which both callers use unchanged. What would be shared is a status check and an exception wrap,
  and the two fetches genuinely differ — `http-regex` is deliberately unauthenticated over the
  public CA, while this kind has full `auth`/`ca-cert` parity. Unifying means either granting
  `http-regex` a hardening it does not offer, or a seam with a parameter one caller never uses.

## Consequences

- The kind gets full transport parity with `http-json` and `http-header` — `auth` (basic / bearer /
  bearer-from-file), `ca-cert`, `insecure-skip-tls-verify`, and ADR-0029's redirect rules — through
  the shared `HttpTransportConfig`, constructed with an `http-prometheus` label so its messages name
  this kind. Blackbox behind an authenticating proxy therefore needs no new work.
- Nothing in this kind fails the boot. A missing or blank `metric` degrades the `current` side as a
  `ConfigError`, as `url` does; a `regex` that does not compile or has no capture group degrades the
  same way through `RegexVersionExtractor`'s `IllegalArgumentException` (ADR-0032).
- `strip-prerelease` is honoured, as it is for `http-json`, `http-header`, `k8s-image` and
  `oci-registry`.
- `conf-check` learns the kind in step, per ADR-0031's rule that the pre-deploy gate and the backend
  never disagree about what a kind is called: `ConfigFileValidation` gains its required-field branch,
  and a new `metric` subcommand validates a metric/selector/label triple against a live URL or a
  saved `curl /metrics` fixture. That command earns its place more than `header` did — a header
  endpoint can be eyeballed with `curl -I`, an 800-line metrics body cannot, and a selector has no
  other way to be checked before deployment.
- If a `latest`-leg need for metric-published versions ever appears, the parser is already
  leg-neutral in substance; it stays in the kind's package until a second caller exists, following
  how `RegexVersionExtractor` was extracted only once `http-header` genuinely needed it.
