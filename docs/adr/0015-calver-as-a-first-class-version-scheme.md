---
status: proposed
---

# Calendar versions are a first-class scheme, not coerced semver

Monitoring whole VMs (Ubuntu, OpenWRT) means comparing calendar versions —
`24.04`, `23.05.5` — that are not semver. The `Version` primitive wraps
semver4j's strict `Semver`, and `new Semver("24.04")` *throws* (two components,
leading zero `04`); every such string would fail its app's scrape today. The
cheap fix is `Semver.coerce` (`24.04 → 24.4.0`, `23.05.5 → 23.5.5`), which orders
correctly. We deliberately **reject coercion** and add calendar versioning as a
genuinely separate scheme.

An Application now declares its scheme, defaulting to today's behaviour:

```yaml
- name: my-openwrt
  version-scheme: calver           # default: semver
  calver-format: "YY.0M.MICRO"     # required when scheme = calver
  current: { type: ssh-os-release, ... }
  latest:  { type: http-regex, ... }
```

The decision rests on a few points:

**Explicit over implicit is the actual payoff.** Coercion orders these versions
fine, so the case for calver is not correctness of the boolean — it is that a
reader of the code (and config) can *see* that an app is calendar-versioned,
rather than reverse-engineering it from a coercion that silently rewrites
`24.04` into `24.4.0`. Coercion lies three ways: it mangles the displayed value,
it implies semver semantics that aren't there, and it hides intent. We pay real
contextual weight — a second version structure in the core — to buy that
clarity. That is the trade we are making on purpose.

**A polymorphic version value, semver untouched.** A sealed `VersionValue`
gains a `CalverVersion` alongside the existing semver logic (now `SemverVersion`);
the core service compares through the interface and stays oblivious to scheme.
`Version.of(raw, scheme, format)` constructs the right one and is plumbed from
the `VersionSourceResolver`, which already builds both legs per app — so the
scheme reaches current and latest from one place and they cannot disagree.

**The scheme is app-level and shared by both legs.** Current and latest must be
commensurable to compare, and the scheme (and, for calver, the format) is the one
thing the two legs may never differ on. It lives on the Application, not on each
`VersionSource`, removing the mismatch footgun. Comparing across schemes, or
across two different calver formats, is a configuration error — refused, never a
silent guess.

**The full calver.org token set, with display fidelity.** All calver tokens are
supported (`YYYY`/`YY`/`0Y`, `MM`/`0M`, `WW`/`0W`, `DD`/`0D`, `MAJOR`/`MINOR`/
`MICRO`/`MODIFIER`). The `calver-format` declares component order; parsing
validates a string against it (a non-matching string fails loudly), comparison is
positional-numeric in token order, and the **original string is preserved for
display** — `24.04` is shown as `24.04`, never normalised. This display fidelity
is the one concrete capability coercion could not provide.

**Drift grading is by token category.** Calver has no native PATCH/MINOR/MAJOR,
but Drift expects a grade. We map by what the changed token *means*: year tokens
→ MAJOR, month/week/day tokens → MINOR, MICRO/MODIFIER → PATCH. When several
tokens differ, the grade is the **most significant category among them** (a
changed year outranks a changed micro). This was chosen over a positional
"first differing index" rule because it reads the way a human reasons about a
calendar version. A consequence is that a date-only format (e.g. Ubuntu's
`YY.0M`) never produces a PATCH — there is no micro band to land in.

## Considered Options

- **Coerce into semver (`Semver.coerce`)** — rejected. Orders correctly and is
  almost free, but rewrites the displayed version, implies semver semantics that
  don't hold, and leaves the calendar-versioned nature of an app invisible in the
  code. The whole point of this ADR is to stop doing the implicit thing.
- **Name the coercion `calver`** — rejected as dishonest: a value that performs
  semver coercion must not claim to be calendar versioning. Once the mechanism
  genuinely parses calendar tokens, `calver` becomes the honest name — which is
  exactly the scheme this ADR builds.
- **Positional-numeric comparison (split on dots, compare integers)** — rejected:
  it would give correct ordering and display fidelity with no declared format, but
  it doesn't know year-from-micro, so it cannot grade by category and cannot
  honestly be called calver. Its honest name would be `numeric`; we don't need it.
- **A polymorphic scheme with positional (index-based) grading** — rejected in
  favour of token-category grading, which matches human reasoning about calendar
  versions.

## Consequences

- The core gains a second version structure. This is real ongoing weight, accepted
  for the explicitness it buys; `coerce` is deliberately *not* added as a third,
  cheaper-but-implicit option.
- `MODIFIER` is the only non-numeric token (e.g. `-rc1`, `-dev`); it orders
  prerelease-style (a build with a modifier sorts below the same build without)
  and grades PATCH. This needs dedicated tests.
- The token grammar and per-token parsing/comparison must be thoroughly tested
  across the full token set, padded and unpadded, including three-digit short
  years (`YY` for 2106 = `106`) and length-differing comparisons.
- A `calver` app whose configured `calver-format` doesn't match the string read
  from its source fails that app's scrape with a precise reason — the same
  per-app isolation that already applies to unparseable semver.
