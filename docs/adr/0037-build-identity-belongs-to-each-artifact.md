# Build identity belongs to each artifact, not the deployment configuration

The backend `/api/version` and frontend `/version.json` report their own embedded
build identity, anonymously and without monitoring dependencies. Although both
images publish in lockstep, one service must not report the other's version:
independent deployments and rolling updates can disagree legitimately.

Runtime configuration, OCI tags, and labels describe a deployment's claim rather
than what the responding artifact actually contains. The backend therefore uses
a generated Java constant (also available in native images), while Vite emits
static JSON packaged with the frontend. Neither is an overridable configuration
property. Release tags supply strict SemVer minus the leading `v`; local and
edge builds report `dev` and `dev-<commit>` without fabricated semantic versions.

These minimal public routes permit ordinary `http-json` monitoring even when
Surface authentication is enabled; external proxy access rules remain the
operator's responsibility. `/api/v1/version` still projects the monitored fleet.
Self-monitoring stays opt-in and uses existing sources, with the existing stable
GitHub release-selection policy. Readiness and replica aggregation are unchanged.
