---
status: accepted
---

# Image tag suffixes are removed by a source-level `regex`, not by a version scheme

LinuxServer.io publishes images tagged `<upstream-version>-ls<N>`, e.g.
`lscr.io/linuxserver/sonarr:4.0.15.2941-ls290`, next to floating tags
(`latest`, `develop`, `version-4.0.15.2941`). `-lsN` is LinuxServer's packaging
build number: the same upstream release, rebuilt. Issue #93 asked for support.
Today every such app fails: `dotnet` rejects the suffix outright, and under
`semver` the suffix is a prerelease, so `oci-registry` skips every tag by
default.

We add an optional `regex` key to the `k8s-image` current source and the
`oci-registry` latest source. Capture group 1 of the first match is the
version. On `oci-registry`, a tag that does not match is not a candidate; on
`k8s-image`, a running tag that does not match fails the scrape.

```yaml
- name: sonarr
  version-scheme: dotnet
  current:
    type: k8s-image
    namespace: media
    workload: deployment/sonarr
    container: sonarr
    regex: '^(\d+(?:\.\d+){1,3})-ls\d+$'
  latest:
    type: oci-registry
    registry: lscr.io
    repo: linuxserver/sonarr
    regex: '^(\d+(?:\.\d+){1,3})-ls\d+$'
```

## Why not a pre-release

Treating `-ls290` as a pre-release gets the meaning wrong. Semver orders
`1.2.3-ls290` *before* `1.2.3`, but the image is a later build of that release.
Semver compares alphanumeric identifiers as text, so `ls1000` sorts below
`ls290`. And `prerelease-filter` matches a segment exactly, so no fixed filter
selects a number that changes on every build.

## Why not in a scheme

ADR-0036 decided that `dotnet` has no pre-release concept, and this does not
reopen it. The suffix is also not tied to one scheme: LinuxServer adds it to
semver, dotnet and calver upstreams alike. It is part of how an image publisher
names tags, like the leading `v` that every parser strips. A scheme-level fix
would need one copy per scheme; a source-level one is written once, where the
tag is read.

## The build number is not drift

`4.0.15.2941-ls290` and `4.0.15.2941-ls291` both capture `4.0.15.2941`, so they
compare equal and the app is up to date. PU2D tracks the application's version,
not image rebuilds. This is the same call ADR-0036 made for the dotnet
revision. On `oci-registry` such tags tie; as in ADR-0036 the tie is not
broken, and here every tied tag captures the same string anyway.

The displayed version is the captured value (`4.0.15.2941`), not the full tag.

## Composition with the other knobs

`prerelease-filter` and `strip-prerelease` apply to the captured version, after
the regex. So `^(.+)-ls\d+$` with `prerelease-filter: alpine` selects
`1.2.3-alpine-ls5` and reports `1.2.3-alpine`.

## Consequences

- One `regex` key now means "capture group 1 is the version" on five kinds
  (`http-regex`, `http-header`, `http-prometheus`, `k8s-image` and
  `oci-registry`), all through the shared `RegexVersionExtractor`. A bad pattern is a per-app
  config error (ADR-0032).
- `oci-registry` uses first-match per tag, not largest-match: each tag is one
  observation, and the selection across tags is still largest-wins (ADR-0014).
- A LinuxServer rebuild with the same upstream version is never reported as an
  update.

## Considered Options

- **Model `-lsN` as a pre-release** — rejected: wrong ordering, see above.
- **A pre-release concept in `dotnet`** — rejected: contradicts ADR-0036 and
  solves the problem for one scheme only.
- **A `tag-format: linuxserver` preset** — deferred: it would be shorthand for
  the regex above, and can be added later if the regex proves error-prone.
- **Compare the build number as a packaging revision** — rejected: it would
  report rebuilds as updates, which operators do not act on.
