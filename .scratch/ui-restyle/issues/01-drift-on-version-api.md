# [01] Drift on the version API

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
Widen the `GET /api/v1/version` HTTP response so each application carries its drift
severity and outdated flag, not just current/latest strings. The domain already computes
this (`VersionApplication.isOld()` and `.drift()` → `Version.Diff`); the HTTP surface just
under-projects it. Mirror the existing MCP `ApplicationView(name, current, latest,
outdated, drift)` projection so all surfaces expose the same scrape state.

After this slice, `curl /api/v1/version` returns, per app, an object shaped like
`{ current, latest, outdated, drift }` where `drift` is the `Version.Diff` name
(`NONE` | `PATCH` | `MINOR` | `MAJOR`). Because `Version` rejects non-semver input at
construction, drift is always exactly one of those four — there is no unknown case to
represent.

The stale `platform-up-2-date.yaml` OpenAPI spec is intentionally left untouched (it
already does not describe this hand-written endpoint).

## Architectural surface
- Use cases: Get application versions
- Ports: `ApplicationVersionPort` (unchanged)
- Adapters: `VersionController` (`GET /api/v1/version`), `ApplicationStatus` DTO

## Acceptance criteria
- [ ] `ApplicationStatus` exposes `current`, `latest`, `outdated` (boolean), `drift` (string).
- [ ] `VersionController` populates `outdated` from `app.isOld()` and `drift` from `app.drift().name()`.
- [ ] An up-to-date app reports `outdated: false`, `drift: "NONE"`; an outdated app reports `outdated: true` and the matching severity.
- [ ] A controller-level test asserts the new fields for at least an up-to-date and an outdated app.
- [ ] The `platform-up-2-date.yaml` spec is not modified.

## Blocked by
None — can start immediately.
</content>
