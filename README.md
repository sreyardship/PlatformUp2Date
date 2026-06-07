# Platform-up-2-date
## Build for development

Simplest way to build and run the whole application is using Docker. An unfortunate pre-requisite, as of now, is that the backend application has to be build before hand.

Navigate to the backend folder and build using gradle:

```bash
$ cd backend
$ gradle build
```

Then, go back to the root folder and run the docker compose:

```bash
$ cd ..
$ docker compose up -d
```

Verify that the two containers are up and running:

```bash
$ docker ps
```

Then go to localhost:3000 using your favorite browser, et voila!

## API

We're using a contract-first approach between the frontend and backend, where the API is first defined in a json-schema using [API Curio](https://www.apicur.io/), then source files generated for both frontend and backend respectively using [OpenAPI Generator](https://github.com/OpenAPITools/openapi-generator/blob/master/modules/openapi-generator-gradle-plugin/README.adoc). These generated files are to be viewed as immutable source files.

The API Curio studio can easily be hosted locally by running the following command from root:

```bash
$ docker compose -f "compose.apicurio.yml" up -d
```

then [open it up](http://localhost:8888) in your browser. The schemas does not persist, however, so make sure to download them before tearing down the containers.

## Metrics & Alerting

The backend exposes a Prometheus scrape endpoint at `/metrics`, hand-rendered in the
Prometheus text exposition format (no Micrometer). The custom metric for version
monitoring is a single gauge:

```
# HELP platformup2date_version_drift_level How far the deployed version is behind latest (0=current, 1=patch, 2=minor, 3=major)
# TYPE platformup2date_version_drift_level gauge
platformup2date_version_drift_level{app="argo-cd"} 3
platformup2date_version_drift_level{app="git-tea"} 0
```

One gauge answers both questions: whether an app is outdated, and how far behind it is.
The value encodes the highest-significance semver difference between the deployed and
latest version — `0` current, `1` patch behind, `2` minor behind, `3` major behind.
(Pre-release/build-only differences are reported as `1`.)

### Scraping

In the cluster the endpoint is scraped via a Prometheus Operator `ServiceMonitor`
targeting the backend `Service` on its `http` port at path `/metrics`. Both live in
the deployment manifests under `apps/service-spaces/platformup2date/` in the
jumziCluster repo.

### Prometheus alert rules

```yaml
groups:
  - name: platform-up-2-date
    rules:
      - alert: AppOutdated
        expr: platformup2date_version_drift_level > 0
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.app }} is behind its latest release"

      - alert: AppMajorVersionBehind
        expr: platformup2date_version_drift_level >= 3
        for: 1h
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.app }} is a major version behind latest"
```

For more detail than the gauge carries (the actual current/latest version strings),
use the frontend or the `GET /api/v1/version` endpoint.
