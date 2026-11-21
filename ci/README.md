# GitHub Actions build identity and image checks

These are local/CI helper scripts, not the retired Tekton pipelines (ADR-0023).
Run from the repository root:

```bash
bash ci/test-resolve-build-version.sh
bash ci/check-backend-build-version.sh
bash frontend/ci/bin/test-build-version-image.sh
bash ci/test-native-dockerfile-arch.sh
bash ci/check-image-platforms.sh linux/amd64,linux/arm64 <image-ref>...
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
- The native Dockerfile test requires Docker (BuildKit) and `readelf`. It builds
  only the `patcher` stage of `Dockerfile.native-ci` with a fixture binary: each
  supported target platform gets its ELF loader, `/nix/store` paths are removed,
  and an unsupported platform fails the build. It needs no native build and no
  emulation.
- `check-image-platforms.sh` reads each given published tag from the registry
  and fails unless its manifest list carries every given platform. The publish
  workflow runs it on each tag it writes (ADR-0043).

Publishing resolves once in `build-images.yml`'s upstream identity job. Every
build job (the backend job of each architecture and the frontend job) checks
out that job's resolved source commit and consumes its version output, with
required-injection flags enabled. PRs exercise the same injection
contracts without GHCR login or write permissions. Native PR integration tests
use `1.2.3-rc.6+ci.1`; their assertions belong to the backend integration suite.
