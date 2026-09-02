---
status: accepted
---

# Every HTTP source kind names its extraction, not its transport: `http` becomes `http-json`

The `http` current source has never been "the HTTP kind" — it is "http, parsed as
JSON via a JSON Pointer" ([ADR-0007](0007-json-pointer-current-version-extraction.md)).
That was harmless while it was the only HTTP-fetching kind. It stopped being harmless
once [ADR-0017](0017-http-regex-latest-source.md) added `http-regex` and
[ADR-0030](0030-http-header-current-source.md) added `http-header`, both named under an
honesty rule ("the name describes the transport *and* the operation") that `http` itself
does not satisfy. Three siblings share one transport, and only two of the three names say
how they differ.

We therefore **rename the kind to `http-json`** and adopt the rule generally: an HTTP
source kind's name states where in the response it finds the version. This is a breaking,
pre-1.0.0 change to an operator-facing `type` string, taken now precisely because it is
still one documented config edit rather than a deprecation cycle across deployed
ConfigMaps.

```yaml
current:
  type: http-json          # was: http
  url: https://container-registry.example.com/api/v2.0/systeminfo
  version-key: /harbor_version
```

Nothing else changes. `http-json` remains a `current`-leg kind, keeps `version-key` and
its `/version` default, keeps its 2xx gate, and keeps the transport hardening of
[ADR-0008](0008-authenticated-http-current-source.md),
[ADR-0012](0012-http-current-source-file-token-and-custom-ca.md) and
[ADR-0029](0029-authorization-does-not-cross-redirect-origins.md) unchanged. This is a
change to the configuration surface, not to any runtime semantics.

## The unification proposed in #51 is declined

ADR-0030's consequences anticipated more than a rename: collapsing all three kinds behind
a single `http` kind carrying a `parse` sub-option (`json` / `regex` / `header`), usable on
either leg. That is issue #51's proposal, and it is **deliberately not adopted**; #51 is
closed by this rename.

The unification's real prize was the *leg* axis, not the *kind* axis — today `http-json` is
current-only and `http-regex` latest-only by accident of what each was built for, and a
`parse` fragment would free both. But it buys that by moving the collapsed axes rather than
separating them: the largest-match/first-match selection rule and the status-code gate would
both become properties that vary invisibly with the leg or the parse sub-type, so an
identical `parse:` block under `current:` and under `latest:` would mean two different
things. The naming dishonesty is real and cheap to fix; the shape change is a larger,
riskier edit whose benefit is a cross-leg combination no configured application currently
needs.

If a concrete need for a regex-scraped *current* version or a JSON-parsed *latest* endpoint
appears, the answer is to widen that kind to the other leg — the sources are already
leg-neutral in substance (`CurrentVersionSource` and `LatestVersionSource` are structurally
identical single-method ports) — not to relitigate the discriminator's shape.

## Retiring a name is a central edit; adding a kind still is not

ADR-0005 records that `VersionSourceResolver` "never names a `type` string itself", so
adding a source kind is a new factory bean and nothing else. A bare unknown-type failure
would preserve that exactly, at the cost of telling an operator whose ConfigMap still says
`type: http` only that no factory exists for it — which reads as an application bug rather
than as an action they must take, on the current leg of every app at once.

The resolver therefore gains a small map of **retired** kind names, consulted only when no
factory matches, so the boot failure names the replacement:

```
The 'http' version source kind was renamed to 'http-json'; update this app's config.
```

This is a deliberate, bounded erosion of ADR-0005's property: the resolver now names type
strings, but only *retired* ones — a closed, append-only historical set. Adding a kind
still touches no central file; only retiring one does. There is no aliasing and no
back-compatibility: `type: http` fails the boot, as it must, since keeping the misleading
name alive is the thing this ADR exists to end.

## Considered Options

- **Collapse all three kinds behind one `http` kind with a `parse` sub-option (#51)** —
  rejected; see above. The cross-leg win is real but unneeded today, and the sub-option
  relocates the axis collapse instead of removing it.
- **Leave the name as `http`** — rejected: it makes the honesty rule that named `http-regex`
  and `http-header` selectively applied, and the name gets more misleading with each HTTP
  sibling added.
- **Accept `http` as a silent alias for a release** — rejected: it keeps the misleading name
  in service, and needs its own later removal, which is this same breaking change deferred
  and paid twice.
- **A generic unknown-type boot failure, with the rename in the release notes only** —
  rejected: it preserves ADR-0005 intact but hands the operator a message that looks like a
  defect at the moment their whole fleet stops resolving.

## Consequences

- Every deployed `platform-config` ConfigMap using `type: http` must be edited. The failure
  is fail-fast at boot and names the replacement; there is no partial or degraded mode.
- The `current/http/` package becomes `current/httpjson/`, and the classes within it are
  renamed to match. `HttpCurrentTransportConfig` — shared with `http-header` since #54 —
  moves out to the leg-neutral `versionsource/http/` package alongside
  `RedirectFollowingHttpGet`, and drops its `Current` prefix: it is shared transport wiring,
  not a property of the JSON kind.
- The `conf-check` CLI's `config` gate dispatches its pointer surface on the literal
  `"http"`; it moves to `"http-json"` in step, so a pre-deploy gate and the backend never
  disagree about what a kind is called.
- ADR-0007 is amended rather than superseded: its JSON Pointer decision stands untouched;
  only the kind's name changes. ADR-0030's closing consequence — which predicted the
  `parse` collapse — is answered by this ADR, not fulfilled by it.
