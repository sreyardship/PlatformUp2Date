# The Changelog link is a read-time projection from an app-level template

Every Application can carry one operator-configured `changelog-url` template —
an app-level field (sibling of `version-scheme`), never a per-latest-source
option. On read, the template is resolved against the app's stored latest
version and served as a nullable top-level `changelogUrl` alongside the other
projections (`drift`, `outdated`, `resolution`) on every Surface, MCP included —
so an agent can read release notes for breaking changes before update work.
Nothing is stored: the link is a pure function of (template, latest version),
computed in the core and shared by the REST and MCP inbound adapters, exactly
like drift. No template, or no known latest version, means no link (`null`).

Template placeholders follow the app's Version scheme: `{version}` always;
`{major}`/`{minor}`/`{patch}` (semver.org terms) for semver apps; the segment
names of the app's declared `calver-format` (e.g. `{YY}`, `{0M}`, `{MICRO}`)
for calver apps, so zero-padding is defined by the token itself. Because legal
tokens are fully determined by per-app config, every token error is a
fail-fast boot error (the `http-regex` regex-validation precedent); a
token-free template is a legal constant URL (e.g. Ubuntu's release-notes hub,
which always describes the latest release). Real shapes this covers:

```yaml
changelog-url: https://github.com/argoproj/argo-cd/releases/tag/v{version}   # semver, static v
changelog-url: https://documentation.ubuntu.com/release-notes/{version}/     # calver YY.0M
changelog-url: https://openwrt.org/releases/{YY}.{0M}/notes-{version}        # calver YY.0M.MICRO
```

## Considered Options

- **Observation: capture GitHub's `html_url` at scrape time (chosen against).**
  The GitHub Releases API hands back the exact release page of the selected
  release for free, which argues for storing the URL in the snapshot as an
  observation. Rejected because only `github-release` has such ground truth —
  `oci-registry` and `http-regex` would still need templates resolved at scrape
  time, leaving two mechanisms with different semantics. Storing also means a
  `SideObservation`/Valkey schema migration, and a template edit in the
  ConfigMap would not take effect until the next successful scrape of that side
  — surprising behaviour for a display link. So `github-release` gets **no
  automatic default**: the `v` in a tag URL is a static literal in the template
  (a repo's tag scheme does not flip-flop), and an app without a template gets
  no link, whatever its latest source kind.
- **Positional tokens shared across schemes (chosen against).** One token set
  (`{major}` = first dot-segment for both schemes) is simpler but lies for
  calver (`{major}` ≠ "year"), makes zero-padding an implementation subtlety of
  string-splitting, and leaves "app's format has no third segment" detectable
  only at read time. Scheme-faithful tokens make padding definitional and all
  validation static.
- **Projection from an app-level template (chosen).** One mechanism for all
  latest kinds, zero storage change, template edits live on next read, and the
  URL can never disagree with the displayed latest version because it is
  derived from it.

## Consequences

- The changelog column links the **last-known** latest version — a stale side
  with a failed-refresh marker still links the value the row displays.
- Switching an app's latest source kind (e.g. `github-release` →
  `oci-registry`) does not touch its changelog config.
- The projection function lives in the core (pure, no I/O) so REST and MCP
  cannot drift apart — the Surface invariant ("same state, same projections")
  extends to this field.
