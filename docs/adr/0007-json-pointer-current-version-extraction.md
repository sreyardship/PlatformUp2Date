# The HTTP current source locates the version via a JSON Pointer, case-sensitively

The `http` current source ([ADR-0005](0005-version-sources-as-pluggable-factories.md))
deserialised the response into a fixed DTO whose field carried
`@JsonAlias({"Version","version"})` — the version's JSON key was hard-wired and
the only flexibility was tolerating that one capitalisation quirk. Real endpoints
don't cooperate: Harbor's `/api/v2.0/systeminfo` returns
`{"auth_mode":…,"harbor_version":"v2.11.1-6b7ecba1",…}`, where the version lives
under an app-specific key. The source needs to be told *which* key to read, and
that key can be nested.

We make the key configurable as a **JSON Pointer (RFC 6901)**. The client now
returns a raw Jackson `JsonNode`; the source extracts the value with
`JsonNode.at(pointer)`. A new `version-key` config field defaults to `/version`,
so existing flat-`{"version":…}` apps are unchanged; Harbor sets
`version-key: /harbor_version`, and a buried value would be `/data/version`.

```yaml
current:
  type: http
  url: https://container-registry.sreyardship.com/api/v2.0/systeminfo
  version-key: /harbor_version
```

A malformed pointer (e.g. missing the leading `/`) fails fast in the factory's
`create()` at startup, alongside the existing non-blank-`url` check; a
*syntactically valid* pointer that doesn't resolve against a given response is a
per-app scrape failure, isolated like any other bad upstream read.

## Considered Options

- **A bespoke dotted path (`data.version`)** — rejected. It reads more naturally
  for the common case but forces us to invent an escape grammar the moment a key
  contains a literal dot (`{"app.version":…}`), and to hand-write the traversal.
  JSON Pointer is a standard Jackson already implements, its `/` separator means
  dots in keys need no escaping at all, and `~0`/`~1` cover the rare literal
  `~`/`/`. The only cost is the slightly less obvious leading slash.
- **JSONPath (Jayway `$['app.version']`)** — rejected: a third-party dependency
  and far more query machinery (filters, wildcards, recursion) than "pick one
  value out of an object" needs.
- **Keeping the case-insensitive `Version`/`version` tolerance** — rejected as a
  consequence of choosing `JsonNode.at()`, which matches keys case-sensitively.
  Re-adding case-insensitivity would mean abandoning the standard pointer for a
  hand-rolled, case-folding traversal — trading a real standard for a minor
  convenience. Endpoints publish their key with stable casing; matching it
  exactly is reasonable.

## Consequences

- The dropped alias is a behaviour change for one existing app: Argo CD's
  `/api/version` returns `{"Version":…}` (capitalised), so its config must now
  carry an explicit `version-key: /Version` or its scrape breaks. This is the
  one quirk the old `@JsonAlias` silently absorbed; it is now visible in config.
- `version-key` and the companion `strip-prerelease` flag join the shared
  `VersionSource` config union as optional fields, read only by the `http`
  factory — the same "union of type-specific fields" pattern by which
  `namespace`/`workload`/`container` are present-but-ignored for non-k8s sources.
