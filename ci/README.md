# GitHub Actions build identity checks

These are local/CI helper scripts, not the retired Tekton pipelines (ADR-0023).
Run from the repository root:

```bash
bash ci/test-resolve-build-version.sh
bash ci/check-backend-build-version.sh
bash frontend/ci/bin/test-build-version-image.sh
```

- `resolve-build-version.sh release v<semver>` validates strict SemVer and
  prints the tag minus its leading `v`, preserving prerelease/build metadata.
- `resolve-build-version.sh edge` prints `dev-<full HEAD>` from the current
  worktree, never from `GITHUB_SHA`. Run it after checkout.
- The CLI tests use real temporary Git repositories, including detached HEAD
  and changed commits. Invalid input fails with no identity on stdout.
- The backend check requires Gradle 8.14.4 on Java 21 and Python 3. It rejects
  missing/invalid required injection, then rebuilds in `backend/build/ci-version`
  without cleaning or forcing task reruns, starting each packaged JVM artifact
  and checking `/api/version`. Valkey and version-source URLs are deliberately
  unreachable. Runtime identity overrides must be ignored.
- The frontend check requires Docker, curl, and Node; it checks the static
  artifact through both shipped nginx configurations. It never publishes images.

Publishing resolves once in `build-images.yml`'s upstream identity job. Both
build jobs check out that job's resolved source commit and consume its version
output, with required-injection flags enabled. PRs exercise the same injection
contracts without GHCR login or write permissions. Native PR integration tests
use `1.2.3-rc.6+ci.1`; their assertions belong to the backend integration suite.
