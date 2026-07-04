# One application-info metric carries fleet membership and version strings

The shipped Grafana dashboard needs two things the three existing metric
families cannot express: exact fleet counts (total / unresolved — the drift
family omits Unresolved apps by design, and the timestamp families omit
never-attempted apps entirely, so a fleet of never-scraped apps is
indistinguishable from an empty fleet) and the current/latest version strings
shown in the frontend table. We add a single info-style family:

```
pu2d_application_info{app="grafana", current="11.0.0", latest="11.1.0"} 1
```

emitted for **every configured Application**, with an unread side rendered as
an empty label value (Prometheus treats an empty label as absent). Total apps
is `count(pu2d_application_info)`; Unresolved is the subset with an empty
`current` or `latest` label. This keeps `CONTEXT.md`'s "Unresolved is a
first-class state" and "never show an empty fleet" true on the metrics
surface, as `docs/adr/0019` already made them true in the snapshot.

## Considered Options

- **Two metrics — existence (`{app} 1`) plus versions only when resolved
  (chosen against).** Cleaner existence/observation separation, but every
  dashboard table needs a join across two families and the Unresolved count
  needs an `unless on(app)` expression. One family is one join target.
- **No version labels (chosen against).** Avoids series churn on version
  bumps, but leaves the dashboard table unable to answer "what version is it
  on?" — the frontend table's most-read columns. For a fleet of this size a
  new series per version bump is negligible.
- **One combined info metric (chosen).**

## Consequences

- Metric names and label shapes are a public contract once dashboards and
  alerts reference them; renaming later breaks consumers silently.
- A version bump ends one series and starts another (label churn). Queries
  over the info family must use `last_over_time`/instant semantics, never
  rate-style functions.
- Drift remains **undefined** for Unresolved apps: they appear in
  `pu2d_application_info` but stay absent from `pu2d_version_drift_level`.
