# Version scheme authority is configuration; scrape state stores bare strings

A rehydrated snapshot once crashed `GET /api/v1/version` with a
`ClassCastException`: the Valkey store deserialised every stored value as
`SemverVersion`, and the calver app openwrt-router's changelog template cast it
to `CalverVersion`. The first fix (ade8e88) made the snapshot self-describing —
persisting `versionScheme`/`calverFormat` per app and trusting them on read,
defaulting missing fields to semver. That default reproduced the exact crash on
every pre-fix snapshot, and worse, a wrongly-typed carried-forward value would
re-persist its wrong scheme on each failed scrape and never converge.

We decided the Application's configuration is the *only* scheme authority. The
snapshot stores observed version strings plus timestamps and nothing else; on
read, each value is retyped by the app's config-derived `VersionParser` (built
once per app in an eager `VersionParsers` bean, shared with the scrape legs).
This makes the changelog-template cast safe by construction — template and
value derive from the same config — and dissolves carry-forward poisoning,
since a carried value is just a string retyped from config on every read.

## Considered Options

- **Self-describing snapshot (chosen against).** Persist scheme fields and
  trust them on read. Two authorities that can drift; any default for legacy
  data is a silent guess, which the glossary's *Version scheme* entry forbids.
  This was tried in ade8e88 and reverted — do not re-add the fields.
- **Config-only rehydration (chosen).** See above.

## Consequences

- A snapshot entry whose app is no longer configured has no declaration to
  interpret under and is dropped at read time — a config removal takes effect
  on the next read, not the next full scrape.
- A stored value that fails to parse under the app's current scheme (e.g. the
  operator flipped semver → calver) makes that side value-less: the app shows
  as Unresolved and self-heals on the next scrape. One bad string never fails
  the whole snapshot read.
- The snapshot is not portable without its configuration; anyone inspecting
  Valkey sees uninterpreted strings by design.
