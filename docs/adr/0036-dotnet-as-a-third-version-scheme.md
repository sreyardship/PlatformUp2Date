---
status: accepted
---

# `dotnet` is a third version scheme, and the revision is not part of it

Sonarr, Radarr and Prowlarr publish four-part versions — `4.0.20.3014`,
`6.4.4.10685`, `2.6.5.5623` — which are `System.Version` values, not semver.
Semver rejects a fourth numeric component outright, so every such app's scrape
fails today. Calver cannot express them either: nothing in `4.0.20.3014` is a
calendar field. We add `dotnet` as a third `VersionScheme`.

```yaml
- name: sonarr
  version-scheme: dotnet
  dotnet-compare-build: true       # the default; false leaves a major.minor comparison
  current: { type: http-json, ... }
  latest:  { type: github-release, repo: Sonarr/Sonarr }
```

## Why a third scheme rather than widening semver

ADR-0015 rejected `Semver.coerce` and accepted a budget for scheme count as the
price of being explicit. This spends more of that budget, for the same reason and
with the same reasoning: coercing `4.0.20.3014` into semver would have to either
drop the revision or fold it into build metadata, and either choice silently
rewrites a version an operator can read on the app's own About page. A declared
scheme keeps the shape visible in the config, and `value()` displays the string
the upstream published.

The alternative — teaching `SemverVersion` a fourth component — was rejected
because it would make `SemverVersion` the union of two grammars and force every
semver app to carry the widened comparison rules. A sealed `VersionValue` already
supports a third member at no cost to the other two.

## Absent trailing components count as zero

`6.2`, `6.2.0` and `6.2.0.0` compare equal, and are equal by
`equals`/`hashCode`.

This is a deliberate divergence from the namesake. `System.Version` stores
undefined components as `-1` and documents that *"if the build or revision number
of a Version object is undefined, that Version object is considered to be earlier
than a Version object whose build or revision number is equal to zero"* — so .NET
itself orders `1.1 < 1.1.0 < 1.1.0.0`. That rule exists to distinguish assembly
*identities* for binding. PU2D compares *published releases*, where writing fewer
digits is formatting, not precedence. A literal mirror would manufacture drift out
of punctuation: an app reporting `6.2` would read as behind a release tagged
`6.2.0.0`, forever, with no upgrade that could fix it.

## The revision is never compared

`4.0.20.3012` is **up to date** against `4.0.20.3014`.

`System.Version` documents that assemblies differing only in revision are
*"intended to be fully interchangeable"*. A revision bump is therefore not drift,
and reporting it as drift would put an app permanently behind on a component its
own publisher treats as noise. The revision is still parsed, still displayed
verbatim, and still addressable as a `{revision}` changelog placeholder — it
carries no drift signal, not no information.

Upstream issue #82 asked for *"compare every numeric component numerically"*.
This ADR **declines that criterion** for the revision specifically; the divergence
is the decision, not an oversight.

## The build is compared by default

`dotnet-compare-build` defaults to `true`. Sonarr's `4.0.17` → `4.0.20` is a
same-major, same-minor upgrade and the motivating case in #82; a default that
leaves it unreported is a default nobody wants. `false` drops the build, leaving a
major.minor comparison for operators who only want to hear about feature
releases. Under `false` an app can never grade `PATCH` — an expected consequence,
the same shape as ADR-0015's note that a date-only calver format never grades
`PATCH`.

## `equals` is comparison-consistent

`equals`/`hashCode` ignore exactly what the comparison ignores: the revision
always, and the build under `dotnet-compare-build: false`. Two values that compare
equal are equal.

This deliberately does **not** copy `SemverVersion`, where build metadata is
ignored for ordering but significant for `equals` (semver4j's contract). That
split is a latent trap — a set or map can hold two members that no comparison can
separate — and is not a model to follow in a new value type.

## Grading maps to the compared components

The most significant differing component decides: major → `MAJOR`, minor →
`MINOR`, build → `PATCH`. The revision cannot produce a grade, so a
revision-only difference grades `NONE`, consistent with the comparison.

## Pre-release is absent, not empty-for-now

`System.Version` has no pre-release concept. `preReleaseSegment()` is always
empty and `withoutPreRelease()` returns `this`, which makes a source-level
`strip-prerelease: true` on a `dotnet` app a silent no-op: meaningless rather than
contradictory, and free to leave alone.

An `oci-registry` `prerelease-filter` is a different matter — it *narrows* the
eligible tag set to tags whose pre-release segment equals the filter, so under
`dotnet` no tag can ever match and the app's scrape would fail forever. That
combination is refused as an `APP`-scope configuration error instead, per
ADR-0032's shape for a defect that is known when the sources are assembled and
never self-heals.

The refusal is keyed on the knob being configured at all, not on the latest kind
being `oci-registry`. Only `oci-registry` reads `prerelease-filter` today, so on
any other kind the knob is inert rather than harmful — but under `dotnet` the
request is unsatisfiable whichever kind is declared, and `VersionParsers` does not
dispatch on `type` strings. Refusing the knob outright says so once, in the one
place that knows the app's scheme; the alternative couples a scheme rule to a kind
name and would go quietly wrong the day a second kind reads the knob.

## Selection ties are accepted, not broken

`OciTagSelector` and `GithubReleaseLatestSource` both reduce with
`current.isOlderThan(candidate) ? candidate : current` — strictly older, so on a
tie the first tag encountered wins. Because the revision is not compared,
`v4.0.20.3012` and `v4.0.20.3014` tie, and which of the two is reported as latest
depends on GitHub's pagination order or the registry's tag listing order.

We accept this rather than adding a tie-break. A tie cannot change an app's
monitoring status — every tied tag is by definition equally current — so the only
observable effect is which of several equivalent strings is displayed. A
revision-aware tie-break would reintroduce, in the selectors, precisely the
comparison this ADR removed from the value type, and the two would then disagree
about what "newer" means.

## Considered Options

- **Coerce four-part versions into semver** — rejected, for the reasons ADR-0015
  rejected coercion: it rewrites the displayed value and hides the app's actual
  version shape.
- **Widen `SemverVersion` to four components** — rejected: it makes one value type
  the union of two grammars and taxes every semver app with rules it does not
  need.
- **Mirror `System.Version` exactly (undefined components sort below zero)** —
  rejected: correct for assembly binding, wrong for published releases, and it
  would manufacture permanent, unfixable drift from formatting alone.
- **Compare the revision, as #82 asked** — rejected: the publisher of the grammar
  documents revision-only differences as interchangeable, and treating them as
  drift puts apps permanently behind.
- **Break selection ties on the revision** — rejected: it changes nothing an
  operator can act on and puts a second, contradictory definition of "newer" in
  the selectors.

## Consequences

- The core carries a third version structure. Accepted on the same terms as
  ADR-0015's second one; the budget is not unlimited, and a fourth scheme should
  have to argue for itself again.
- `dotnet-compare-build: false` makes `PATCH` unreachable for that app.
- Which of two revision-tied tags is displayed as latest is not deterministic
  across scrapes. Documented in `docs/configuration.md`; no tie-break is added.
- A `dotnet` app's `changelog-url` addresses `{major}`/`{minor}`/`{build}`/
  `{revision}`; `{patch}` is illegal for the scheme, since `System.Version` has no
  such component and aliasing it would be a guess.
