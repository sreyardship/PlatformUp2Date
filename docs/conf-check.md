# conf-check

`conf-check` is a standalone CLI that validates the parts of `platform-config.yaml` the backend can only prove wrong at boot or at scrape time. It lets you test a regex, a JSON Pointer, a changelog template, or a calver format before you deploy the config, and it can gate an entire config file in CI.

The CLI lives in the `:conf-check` Gradle module. It depends only on `:domain`, so the validators it runs are the same domain primitives the backend uses (`VersionParser`, `CalverFormat`, `ChangelogTemplate`). If conf-check accepts a value, the backend will too.

## Why it exists

The backend validates config in two places, and both are late:

- Boot: an illegal changelog placeholder or a malformed `calver-format` crashes startup.
- Scrape time: a regex that matches nothing, or a `version-key` pointer that resolves to nothing, only shows up as a failed scrape after deployment.

conf-check moves both checks to the point where you edit the file. You can run a single surface interactively while writing an app entry, or run the `config` gate over the whole file in a pipeline and fail the merge before the config ships.

## Subcommands

| Subcommand | Checks | Needs a body? |
|---|---|---|
| `regex` | An `http-regex` pattern extracts at least one parseable version from a body, and reports which candidate wins | yes |
| `pointer` | A `version-key` JSON Pointer (RFC 6901) resolves to text in a JSON body, optionally also parsing it under a scheme | yes |
| `changelog` | A `changelog-url` template's placeholders are legal and resolve against a given version | no |
| `calver` | A calver.org format string parses a sample version, printing the token=value mapping | no |
| `config` | Every app in a `platform-config.yaml`, all four surfaces at once | fetched from the config's own URLs |

`regex` and `pointer` take the body from exactly one of three sources: `--url` (live fetch), `--body-file`, or `-` for stdin. `changelog` and `calver` are pure functions of their arguments; they never touch the network.

### Examples

Check that a release-page regex finds a version, with the scheme the app declares:

```
conf-check regex --regex 'v(\d+\.\d+\.\d+)' --scheme semver --url https://example.org/releases
```

Check a pointer against a saved response, without caring about the scheme yet:

```
curl -s https://myapp.internal/info > body.json
conf-check pointer --key /app/version --body-file body.json
```

Confirm a changelog template resolves for a calver app:

```
conf-check changelog --template 'https://example.org/notes/{yy}.{mm}' \
    --version 26.07.3 --scheme calver --calver-format YY.0M.MICRO
```

See how a calver format tokenizes a version you expect to encounter:

```
conf-check calver --format YY.0M.MICRO --version 26.07.3
```

Gate a whole file:

```
conf-check config platform-config.yaml
conf-check config platform-config.yaml --offline   # skip live fetches
```

## Exit codes

Every subcommand prints a human-readable report and exits with a code that encodes the failure kind, so scripts can branch without parsing output:

| Code | Meaning |
|---|---|
| 0 | Everything passed |
| 2 | The config itself is invalid: regex won't compile or lacks capture group 1, illegal changelog placeholder, malformed calver format, bad scheme combination. For `config`, also an unreadable or unparseable YAML file |
| 3 | Body acquisition failed: network error, timeout, non-2xx response, or an unreadable `--body-file` |
| 4 | The config is valid and a body was obtained, but nothing usable came out: zero regex matches, no match parsed under the scheme, or the pointer resolved to nothing |
| 5 | `config` only: the file parsed, but at least one app failed at least one surface |

Code 1 is reserved for uncaught errors (bugs), so a deliberate failure never looks like a crash. The codes are stable; scripts can depend on them.

Code 2 mirrors what the backend rejects at boot, code 3 mirrors a fetch failure at scrape time, and code 4 mirrors the backend's "no parseable version matched" scrape-failure state.

## How the config gate works

`conf-check config <file>` reads every app out of the file and runs up to four surfaces per app:

- regex, when `latest.type` is `http-regex` and both `url` and `regex` are set. Fetches `latest.url` live.
- pointer, when `current.type` is `http`. Fetches `current.url` live and applies the `/version` default when `version-key` is absent, same as the backend.
- changelog, when `changelog-url` is set. Constructs the template to prove every placeholder is legal, but never resolves it: a static file has no current version to resolve against, and the backend's boot check draws the same line.
- calver, when the app declares `version-scheme: calver` with a `calver-format`. Constructs the format to prove it is well-formed.

A surface that doesn't apply to an app (say, the regex surface for a `github-release` latest source) is reported as not applicable and never counts as a failure. `--offline` additionally skips the two fetch-backed surfaces (regex and pointer) so the gate can run without network access; changelog and calver still run, because they need none.

The aggregate exit code is 0 only when every app passed every surface that ran.

## Intended use

Two workflows, one per audience.

When adding an app to `platform-config.yaml`, use the single-surface subcommands as a fast feedback loop. Curl the release page once, then iterate on the regex against the saved body with `--body-file` instead of re-fetching on every attempt. Same for pointers against a saved JSON response.

In CI, run `conf-check config` on any change to the config file. `--offline` catches structural mistakes (bad placeholders, malformed formats, missing required fields) without network flakiness; the full online run also proves the configured URLs actually yield a version today.

conf-check is not a monitoring tool. It validates that a config *can* work, once, at the moment you run it. Continuous scraping, comparison against latest releases, and status reporting belong to the backend.

## Building and getting the binary

Native binaries for tagged releases are attached to each `v*` GitHub Release as `conf-check-<os>-<arch>`, built with GraalVM native-image. Download one, `chmod +x` it, and run it; no JVM required.

To build locally (from the repo root):

```
gradle :conf-check:build            # JAR + start scripts under conf-check/build
gradle :conf-check:nativeCompile    # native binary at conf-check/build/native/nativeCompile/conf-check
```

The native build needs GraalVM with native-image; the dev shell in `project-environment/` provides it.

## Design notes

The module follows the same ports-and-adapters shape as the backend. Commands are composition roots only: each one wires a body source (the `BodySource` port, with live HTTP and offline file/stdin adapters), a validator, and the `ReportRenderer`, then returns the outcome's exit code. Validators depend on ports, never on adapters, which is what lets the `config` gate inject its body-source factory and lets tests substitute canned bodies.

Outcomes are a sealed interface (`ValidationOutcome`), one case per exit-code kind, so the renderer and the exit-code contract are exhaustive by construction. The javadoc on `ValidationOutcome` is the authoritative statement of the exit-code contract.
