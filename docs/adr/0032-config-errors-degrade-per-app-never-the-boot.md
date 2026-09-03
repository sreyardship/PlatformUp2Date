# A config error degrades only what it touches; it never fails the boot

Version-source factories and the startup wiring beans split configuration errors
into two categories with two very different blast radii — a missing `url`,
a malformed `version-key`, an uncompilable `regex`, a bad `calver-format` or an
illegal `changelog-url` killed the **whole boot**, while a bad `auth` fragment or an
unreadable `ca-cert` degraded **one app** — and the line between them no longer
tracked any principle that could be stated. Both are operator typos in the same
ConfigMap, discoverable at the same moment, fixed the same way; one was a fleet
outage and one was a red row.

We replace the split with a single rule:

> **A defect in one Application's configuration degrades only what that defect
> touches. The only configuration failure that stops the application is one that
> makes the config document unbindable.**

Everything else — an unknown or retired source `type`, a blank `url`, an
uncompilable `regex`, a malformed JSON Pointer, an incoherent credential, an
unreadable `ca-cert`, a missing `calver-format`, an illegal `changelog-url` —
becomes a *Config error* (`CONTEXT.md`): recorded at assembly time, surfaced on every
Surface, and scoped to what it actually breaks.

## Scope, not blast radius

A config error carries a **scope**, because "one app degrades" was itself too coarse:

| Scope | Example | Effect |
| --- | --- | --- |
| `current` / `latest` | blank `url`, uncompilable `regex`, unknown `type`, bad `auth`, unreadable `ca-cert` | that side degrades to a `FailedCurrentSource`/`FailedLatestSource`; the other side keeps reading and keeps showing a version |
| `app` | `version-scheme: calver` with a missing or invalid `calver-format` | **both** sides degrade — the Version scheme is declared once per app and shared by both legs, so neither can be parsed |
| `changelog` | an illegal `changelog-url` template | **nothing** degrades: the app scrapes normally, both versions show, the Changelog link is simply absent (ADR-0021 already defines "no template means no link") and the error is reported |

The `changelog` scope is the case that forced the model: a config error with no failed
side to attach a reason to. So config errors are a first-class per-app list, and the
reason shown beside a failed side is one *projection* of that list — not the other way
around.

## What still fails the boot

Two things, both deliberate:

- **An unbindable config document.** `ApplicationConfigLoader`'s SmallRye
  `@ConfigMapping` binds all-or-nothing, so nothing per-app can be rescued from a
  document that will not bind. To make that the *only* case, `AppConfig.name()`,
  `VersionSource.type()` and `Auth.type()` become `Optional` — every other field
  already was — and their requiredness moves into per-app validation. Genuinely
  un-tokenizable YAML remains a boot failure because nothing is knowable from it.
- **A duplicate factory `type()`.** Not operator config at all: two of our own beans
  claiming one kind. No per-app degradation is even definable for it (which app
  degrades?), and our own tests catch it.

An app entry that binds but has **no name** is dropped from the fleet with a log line
and an unlabelled `pu2d_config_unnamed_apps` count. This is an accepted hole, and it
contradicts ADR-0019's rationale for showing Unresolved apps ("a freshly-added,
misconfigured app would be silently invisible — exactly the app a platform engineer
most needs to see"). It is unfixable: reporting an app requires an identity we do not
have.

## Mechanism: one catch, not a contract change

`VersionSourceResolver` wraps every `create(...)` call in a try/catch, records the
`(app, scope, reason)`, and returns the `Failed*Source`. Factories keep throwing with
their existing clear messages and are otherwise untouched — including their unit
tests, whose `assertThrows` assertions stay valid, since what changed is what the
*resolver* does with a throw. An exception we did not declare (an
`NullPointerException`, an Arc failure building a REST client) is caught too, and
logged at ERROR as a defect in our own code rather than WARN as an operator config
error, so the "one app can never take the fleet down" promise holds absolutely.

`Failed*Source` stops being "a thing a factory returns" and becomes **the resolver's
representation of any per-app config error**, constructed in exactly one place — which
it has to be anyway, since an unknown `type` has no factory to construct it.

`VersionParsers`, `ChangelogTemplates` and `VersionSourceResolver` all stop throwing
and start recording, each implementing a `ConfigErrorSource` interface that a
`ConfigErrors` bean aggregates via `Instance<ConfigErrorSource>` — the same
discovered-by-mere-existence idiom ADR-0005 chose for the factories, so a future
error-producing bean is picked up without editing a central file. The boot report is a
`StartupEvent` observer, which fires after every producer is constructed, so
completeness is guaranteed by CDI rather than by construction order. An `app`-scope
error flows *through* the resolver (which needs the reason for both degraded sides)
and is reported once, by `VersionParsers`, not three times.

## Surfacing

The config-error list is **derived from config, never from Valkey**: every replica
loads the same ConfigMap and computes the same answer. It is a projection in the sense
of ADR-0021, so ADR-0019's "failure reason is not persisted" stays true and untouched —
the snapshot still records only *that* and *when* a side failed.

- **REST** — `configErrors: [{scope, message}]` on the app payload, projected on read.
- **Web board** — the reason inline on the affected side; an app-level marker for
  `app` scope; for `changelog` scope the app looks normal but the icon is absent and
  carries the reason.
- **MCP** — a `list_misconfigured_applications` tool, plus `configErrors` on
  `get_application`. `list_applications_with_failed_scrapes` stays purely
  observation-based, as ADR-0019 defined it.
- **Metrics** — `pu2d_config_error{application, scope}`, set once at boot.
- **Logs** — one aggregate WARN at the end of startup, replacing WARNs scattered
  through Quarkus startup noise.

## Considered Options

- **Transient vs permanent** (crash on what can never self-heal; degrade on what
  might, like a Secret absent mid-rollout) — rejected: it is a rule about the *world*,
  not the config, so a factory cannot apply it by inspecting a fragment. It also gives
  no stable answer for `ca-cert`, a static path that can still be briefly absent during
  a remount. A rule that cannot be applied by inspection is how the current split
  drifted in the first place.
- **Fail the boot only if *every* app is misconfigured** — rejected: it makes boot
  success depend on the *other* apps in the ConfigMap, so adding a healthy app would
  flip a crash into a boot. The instinct it serves ("don't ship a fleet monitoring
  nothing") is already served: an all-broken fleet renders as an all-Unknown board.
- **Keep structural-vs-value, move the misfits** — rejected: preserves a boundary that
  does no work. `ca-cert: /wrong/path` is structurally fine and semantically dead;
  `regex: '\d+'` is structurally fine and semantically dead. One degraded, one crashed.
- **Change the factory contract so a factory cannot throw** (lift
  `HttpTransportConfig.Resolution` up to `create()`) — rejected as the primary
  mechanism: type-enforced rather than remembered, but it rewrites both factory ports,
  all seven factories and their tests for an outcome one try/catch already delivers.
- **A config-check launch mode on the server** (boot, print the report, exit non-zero;
  runnable as a CI step or an initContainer) — rejected: it would have restored
  deploy-time fail-fast using the exact same code path, but it gives the server a second
  launch mode, and we chose to accept runtime-only discovery instead.
- **A validation module shared with `conf-check`** — rejected: config-fragment
  validation knows `type` strings, JSON Pointers and `ca-cert` paths, which ADR-0005
  refuses to admit into the core, so sharing means a fourth module. Instead we follow
  the existing `ChangelogTemplate`/`CalverFormat` precedent: a substrate-free rule
  becomes a `:backend:domain` value type both modules construct; a substrate-bound rule
  stays in the server and `conf-check` reports `notApplicable`.

## Consequences

- **A typo now reaches production.** This is the deliberate cost of the rule, and
  nothing replaces the deploy-time gate: `conf-check` validates behavioural surfaces
  (does this regex actually extract from the live page) and knows nothing about
  unknown `type`, `auth` coherence, or `ca-cert`. The trade is a red row carrying a
  clear reason instead of a crash-looping ReplicaSet — which, on a `replicas: 2`
  rolling update, was never an immediate outage anyway but a config change that
  silently did not apply, and a fleet that died later at an unrelated restart.
- **`@ConfigMapping` stops enforcing anything.** If per-app validation forgets a field,
  it fails later and worse instead of at bind time.
- **The regex compile-and-capture-group rule moves to a `VersionPattern` value type in
  `:backend:domain`**, which also exposes the raw group-1 candidates in input order.
  This deletes `conf-check`'s verbatim `RegexPatternValidation` copy — whose own Javadoc
  warned the rule "must not drift into two divergent copies" while being exactly that —
  and folds four copies of the same match loop into one. Each caller keeps its own
  *selection* rule (largest-wins, first-wins, report-all), which is the only part that
  genuinely differs (ADR-0030). `RegexVersionExtractor.firstIn` loses its short-circuit
  on the first parseable match; irrelevant for a header value or a release page.
- **JSON Pointer syntax validation stays in the server.** `:backend:domain` depends on
  semver4j and nothing else; admitting Jackson into the core for one
  `JsonPointer.compile` call is a bad trade, and `conf-check` does not duplicate the
  rule.
- **One integration boot test is the regression guard**: a fixture with one valid app
  and one app per defect class must *boot*, must scrape the valid app, and must report
  exactly the expected `(app, scope, reason)` set. That single "it boots" assertion
  fails loudly the moment anyone reintroduces a boot-fatal config check anywhere.
- **`conf-check`'s header surface** keeps failing CI on a missing `url`/`version-header`,
  but its stated reason ("the backend's factory throws at boot") is now false and its
  comment is corrected.
