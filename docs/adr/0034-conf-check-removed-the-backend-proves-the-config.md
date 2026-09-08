---
status: accepted
---

# The running backend is the only place configuration is proven: `conf-check` is removed

`conf-check` existed to answer two questions before a `platform-config.yaml` reached a
cluster, because the backend answered both of them too late:

- **Is this value legal at all?** An illegal `changelog-url` placeholder or a malformed
  `calver-format` killed the boot. Finding that out from a crash-loop is finding it out
  the worst way.
- **Does this extraction actually pull a version out of the real response?** A regex that
  matches nothing, or a `version-key` that resolves to nothing, surfaced only as a
  *Failed scrape* after deployment.

[ADR-0032](0032-config-errors-degrade-per-app-never-the-boot.md) deleted the first
question. Every one of those defects is now a *Config error*: recorded when the fleet's
sources are assembled, scoped to what it breaks, and reported on every *Surface* —
`configErrors` on the REST payload, the board's red glyph with an always-visible reason,
the `list_misconfigured_applications` MCP tool, and `pu2d_config_error{application,scope}`
for alerting. A pre-deploy check for the same defects is now a second implementation of a
question the backend already answers, out loud, per Application.

The second question survives, and this ADR gives it up:

> **Configuration is proven by the running backend, not before it. There is no
> pre-deploy gate.**

`conf-check` — the `:backend:conf-check` Gradle module, its seven subcommands, its
native binary and its release asset — is deleted.

## The price was per-kind, and rising

ADR-0032 halved the value. What forced the decision is the other side of the ledger:
`conf-check` had to be taught every *Version source* kind, so each new kind cost two
implementations of the same extraction.

`http-prometheus` ([ADR-0033](0033-http-prometheus-current-source.md)) is the worked
example. Landing one current source kind meant a `MetricCommand`, a
`MetricExtractionValidation`, a config-gate surface, a hand-copied `PrometheusExposition`
parser — `:backend:conf-check` cannot depend on `:backend:server` — and a
`PrometheusExpositionCopyFidelityTests` that read the backend's copy off disk to prove the
duplicate had not drifted. A test whose entire job is to police a duplication is the
duplication charging rent.

That cost recurs for every kind that ever ships. Removing the module converts it to zero,
permanently, which no amount of care inside the module could do.

## What is given up, named plainly

Two capabilities go, and neither is replaced:

- **Behavioural verification before deployment.** Nothing proves that a `regex`,
  `version-key`, `version-header` or `metric`/`version-label` extracts a parseable version
  from a real response until the backend performs a *Scrape*. A wrong extraction is
  discovered as a *Failed scrape* on the board.
- **The CI merge gate.** `conf-check config platform-config.yaml` could fail a pipeline
  before a bad config shipped. No check replaces it; a bad config now merges and is caught
  in the cluster.

This is acceptable only because ADR-0032 made it acceptable. A wrong extraction degrades
one side of one Application, keeps its last-known version, and leaves the rest of the
fleet reading normally. The loop is: deploy, read the reason on a *Surface*, edit the
ConfigMap. That is minutes of a red row, not a fleet outage — and before ADR-0032 it would
have been an outage, which is precisely why the gate was worth its price then and is not
now.

## Now, before `v1.0.0`

Every tag to date is a release candidate (`v1.0.0-rc.0` … `rc.4`), and per `RELEASE.md` an
RC publishes only a prerelease: it never moves `latest`, the rolling tags, or the shipped
pins. The `conf-check-linux-amd64` asset has therefore never appeared on a supported
release, and no operator holds a version of it we promised to keep.

Ship `v1.0.0` with the binary attached and that stops being true. Removing it afterwards
becomes a withdrawal of a published artifact, needing a deprecation cycle to do honestly —
the same reasoning ADR-0031 applied to retiring the `http` kind name, and the same
conclusion: pre-1.0.0, so there is no alias and no deprecation cycle. The removal is
therefore timed, not merely queued: it lands before the `v1.0.0` tag or it costs
materially more.

## Considered Options

- **Keep a shrunken CLI — only `config --offline`, or only the behavioural subcommands** —
  rejected: it does not touch the problem. The maintenance cost lives entirely in the
  behavioural surfaces, since those are what each new kind must be taught. Keeping them
  keeps the whole tax and discards only the cheap parts, which are also the parts ADR-0032
  already made redundant.
- **A dry-run endpoint on the running backend** — takes a source fragment and a URL,
  reports what it extracted. Rejected: it is a new client-facing *Surface*, so
  *Surface authentication* grows a case, and it replaces a *pre-deploy* gate with something
  that by construction requires a running backend and cannot gate a merge. More surface
  area to buy back less than was lost.
- **A shared validation module used by both** — already rejected by ADR-0032 for its own
  reasons, and it answers the wrong question here: it would reduce the duplication without
  removing the second binary, its native build, its release job, and its documentation.
- **Freeze the module: keep it, stop teaching it new kinds** — rejected, and it is the
  worst option. `docs/conf-check.md` promises "if conf-check accepts a value, the backend
  will too". A frozen gate breaks that promise the first time a kind ships without it, and
  a validator that silently under-reports is worse than no validator: it converts a missing
  check into a false assurance.
- **Deprecate now, remove after `v1.0.0`** — rejected: it manufactures the obligation it
  then has to discharge. Shipping a documented, advertised binary in 1.0.0 and withdrawing
  it in 1.1 is a worse story for an operator than never shipping it in 1.0.0.

## Consequences

- **Adding a *Version source* kind is now one implementation.** This cancels ADR-0033's
  standing instruction that "`conf-check` learns the kind in step", and the same clause in
  ADR-0031 about the `config` gate's dispatch. A future kind touches the backend and
  `docs/configuration.md`, and nothing else.
- **ADR-0032's open concession is answered, not fulfilled.** It recorded that "nothing
  replaces the deploy-time gate: `conf-check` validates behavioural surfaces". This ADR
  accepts that gap deliberately rather than closing it.
- **ADR-0029 is amended in substance.** Its redirect rule was scoped to "version sources and
  `conf-check`"; one of those two callers no longer exists. The rule itself — bounded
  chains, `Authorization` retained only within one origin, no HTTPS-to-HTTP downgrade — is
  untouched and still binds every outbound GET a *Scrape* makes.
- **ADRs 0031, 0032 and 0033 keep their bodies.** Their statements about `conf-check` were
  true when written and are a record of the past; each gains a note pointing here. Only
  ADR-0029, whose decision statement itself names the module, has its body corrected.
- **`:backend:domain` tightens.** `CalverVersion.displayedValue` was `public` for exactly
  one reason — the `calver` subcommand consumed it across a module boundary, as its Javadoc
  said. Its only remaining caller is `ChangelogTemplate` in the same package, so it becomes
  package-private. `VersionPatternTests` drops the assertion pinning the zero-capture-group
  message verbatim, which existed so `conf-check` could render it without rewriting; the
  sibling test asserting the *property* (the message names no source kind, leg or config
  field) stays, since `RegexVersionExtractor` still relabels that message for three kinds.
- **`VersionPattern` stays exactly as it is.** It remains the single implementation of the
  "compile a regex, require capture group 1" rule; only its Javadoc's `conf-check` examples
  are restated in terms of the callers that remain.
- **The release becomes atomic.** `RELEASE.md` records an accepted defect: the release body
  advertises the `conf-check` asset some minutes before the job that uploads it finishes.
  With the `conf-check-native-release` job gone, a release is complete when it is created.
  GraalVM stays in CI and in `project-environment/` — the backend's own native image needs
  it (ADR-0025).
- **`CONTEXT.md` is unchanged.** `conf-check` was never a term in the ubiquitous language,
  and removing it introduces none.
- **`docs/conf-check.md` is deleted** rather than left as a tombstone, and the advice in
  `docs/configuration.md` and `README.md` to test the harder keys before deploying is
  replaced by a pointer to "When configuration is wrong" — the section that already
  documents how a *Config error* reaches every *Surface*. No `curl` recipes replace the
  subcommands: a per-kind recipe is the same per-kind maintenance in a new costume, and it
  could not apply the Application's *Version scheme* anyway.
