# The API is code-first; the OpenAPI spec is generated, not authored

The project started contract-first: a hand-authored OpenAPI spec
(`backend/src/main/resources/openapi/platform-up-2-date.yaml`, edited in Apicurio
Studio) was meant to drive code generation on both sides. In practice the
contract was never followed — the spec fossilized at inception (it described
`/v1/application(s)` while the real API grew into `/api/v1/version` and
`/api/v1/scrape`), no code ever referenced the generated interfaces, and the
frontend always used hand-written axios clients. Contract-first pays off when a
frontend team and a backend team need to agree on an API before building; this
project is developed fullstack, with both sides changing in the same PR, so the
code *is* the contract.

We therefore flipped to code-first: the hand-authored spec, the
`quarkus-openapi-generator-server` extension and the Apicurio compose file are
deleted, and `quarkus-smallrye-openapi` generates an always-accurate spec from
the JAX-RS controllers instead.

## Considered Options

- **Rewrite the spec and honour contract-first (rejected).** Pays the debt but
  keeps the ceremony that team shape doesn't justify — every API change would
  mean editing YAML, regenerating, and wiring interfaces, for an agreement
  between one person and themselves.
- **Code only, no spec (rejected).** Simplest, but the REST API's audience is
  not only our frontend: agents and tools may consume it directly rather than
  via MCP, and `/q/openapi` is how a non-human consumer learns the API without
  reading Java. The generated spec costs one dependency and zero workflow.
- **Code-first with a generated spec (chosen).** The controllers are the source
  of truth; the spec is a free, never-stale artifact for discoverability, plus
  Swagger UI in dev mode.

## Consequences

- **No generated frontend client.** The axios clients in `frontend/src/api/`
  stay hand-written; their tests are the drift detector between the two sides.
  Generating a typed client from `/q/openapi` would reintroduce the codegen
  pipeline this ADR removes, pointed the other way — deliberately not done for
  a three-endpoint API developed fullstack.
- **The spec is a runtime artifact**, served at `/q/openapi`, not a file in the
  repo. Nothing pins it in CI.
