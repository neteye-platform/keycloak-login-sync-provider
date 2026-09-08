# Keycloak Login Sync Provider

This project provides the Keycloak 26.7.0 `login-sync` custom Authenticator.
On a browser LOGIN, it performs one synchronous POST to an external
syncservice, authenticated with a service-account JWT obtained through OAuth2
Client Credentials. See [the sync contract](docs/SYNC-CONTRACT.md) and
[the decision record](docs/DECISIONS.md) for the governing contracts.

## Build and test

Copy `.env.example` to the git-ignored `.env` before starting the local stack.
The stack is defined in `podman-compose.yml`; the Makefile drives it through
the container runtime's compose command.

Use `scripts/test.sh` as the only supported Maven entry point. It supplies
Java 21 through a container when Maven is unavailable locally. Run the full
verification suite with:

```sh
scripts/test.sh clean verify
```

The Makefile provides the local development loop:

- `make build` builds the provider and compiles the mock service.
- `make up` starts Keycloak and the mock service.
- `make logs` follows logs from both services.
- `make down` stops and removes the stack.
- `make reset` removes the stack, volumes, orphans, and `target/`.
- `make deploy` rebuilds and recreates Keycloak with the new provider.
- `make test` runs the full verification suite.
- `make fmt` applies the Java formatter.

## Configuration

Keycloak reads configuration through `Config.Scope` in the provider factory,
not through `System.getenv`. The double underscore between configuration
segments is required; the form with one underscore does not resolve. Plain
`start-dev` resolves these settings, so a separate `kc.sh build` is not needed.

- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT`
  Scope key `service-endpoint`. Required, no default. Complete receiver URL,
  including the path (for example `http://receiver:8081/api/sync-user`).
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_ID`
  Scope key `sa-client-id`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_SECRET`
  Scope key `sa-client-secret`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_TOKEN_ENDPOINT`
  Scope key `sa-token-endpoint`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__HTTP_TIMEOUT_MS`
  Scope key `http-timeout-ms`. Optional, with a default of `5000` milliseconds.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__ALLOW_INSECURE_HTTP`
  Scope key `allow-insecure-http`. Optional, defaults to `false`. The
  credential-bearing endpoints (`service-endpoint` and `sa-token-endpoint`)
  must use `https://` unless this is `true`. Set it only for a private
  test/dev network; enabling it in production disables TLS transport
  protection for the service-account secret and the sync JWT.

A missing required value degrades the provider to a no-op that permits logins.
A malformed URL or a non-numeric or non-positive timeout deliberately aborts
provider startup with an `IllegalStateException`. The bulkhead limit and token
timeouts are internal constants and are deliberately not operator-facing.

Upgrading from an earlier version is a breaking configuration change: append
the receiver path to an existing base-only `service-endpoint`. The provider now
posts to the configured URL verbatim, consistently with `sa-token-endpoint`.

There is no setting for the PermissionSync target. The target is derived at
runtime from the client the user logs in to, so the list above is complete and
no key was added for it. Do not go looking for one. What the target does require
is provisioning on the service-account client; see the deployment section.

## Deployment

The `login-sync` execution MUST be a top-level **REQUIRED** execution in the
browser flow, placed after the forms subflow because the plugin needs an
authenticated user.

Placing it earlier is rejected by Keycloak. Because `requiresUser()` returns
true, Keycloak raises an error before `authenticate()` is invoked. A
misplacement is a hard failure, not a silent skip, and no null-user guard
inside the authenticator can compensate.

The plugin needs a dedicated confidential client with a service account.
Provisioning that client is out of scope for this repository. The compose
stack carries configuration only and provisions no realm, client, or role.

Authorization is decided by PermissionSync ADR-0002: for logins where a target
scope is requested, the service-account token must carry exactly one
`permissionsync:<target>` scope, the PermissionSync audience, and a `client_id`
claim. (A login with no target carries no `permissionsync:` scope; see the
deployment details below and the "Empty scope is a valid no-op target" section
in [the sync contract](docs/SYNC-CONTRACT.md).) Provisioning that scope and
audience on the service account is an operator task outside this repository.
See [the sync contract](docs/SYNC-CONTRACT.md).

The plugin derives the target from the client the user is logging in to,
and it decides whether that client has a target by looking in the realm the user
is authenticating against. It reads that login realm's client-scope catalog and
checks for a client scope named exactly `permissionsync:<login client id>`. If
one exists, the plugin sends `scope=permissionsync:<login client id>` as a form
parameter on the `application/x-www-form-urlencoded` Client Credentials request
to `sa-token-endpoint`; the issued JWT then carries that scope, and the receiver
routes and authorizes on it. If no such client scope exists in the login realm,
the plugin requests no scope at all, the request body omits `scope`, and the
token carries no `permissionsync:` scope. That empty scope is a valid request:
PermissionSync accepts it as a no-op default target and answers `200` or `204`.

The check looks only at the login realm's catalog. It does not consult the
service-account client's own scope assignments, it does not cross a realm
boundary, and it makes no Admin API call. For a brokered login the
post-broker-login flow does not replace the authentication session's client,
so the target is the original initiating login client, not the
identity-provider client.

For every target an instance serves, the operator MUST provision on the
service-account client:

- a client scope named `permissionsync:<clientId>`, assigned as **Optional**
  and explicitly **not** Default, and
- the PermissionSync audience.

A client with no target needs neither. Provision nothing for it and leave the
login realm without a `permissionsync:<clientId>` client scope; its logins then
travel on an empty scope and are accepted as the no-op default target.

Optional is not a stylistic choice. A Default client scope is applied
unconditionally to every token the client obtains, so a service account serving
two targets would emit two `permissionsync:*` scopes in one token. ADR-0002
requires exactly one, and the contract's answer to more than one is `403`. An
Optional scope is only included when it is requested, which is what the plugin
does. The failure in the other direction is quieter, and it applies only when
the plugin actually asked for something: if the login realm holds a matching
`permissionsync:<clientId>` client scope, the plugin requests that scope, and
the scope is not assigned to the service-account client at all, then Keycloak
omits it from the token without an error, the receiver is left with zero
`permissionsync:` scopes it was supposed to receive, and it must answer `403`.
That is a genuine misconfiguration and is unchanged. It is not the same as a
client that has no target scope in the login realm to begin with, where nothing
was requested and the empty scope is expected.

Using the login client's `clientId` as the target identifier is safe only within
a single trust domain. The provider and its service account are restricted to
the intended realm, and that restriction is what makes the identifier
unambiguous. Two unrelated realms can each hold a client named `glpi`, so a
deployment must not be stretched across trust domains on the assumption that the
name alone identifies a target.

The body is unchanged at exactly three fields, `event_type`, `username` and
`groups`, as fixed by PermissionSync ADR-0001. The target travels only in the
JWT scope and never appears in the body.

Deploy the built JAR into `/opt/keycloak/providers`.

## Limitations

- The service-account token cache is per-JVM, therefore per-node in a cluster,
  and is keyed per scope. There is one cached token per
  `permissionsync:<target>`, so two target clients never share a token.
- While the scope provisioning is wrong, a `403` evicts that scope's cached
  token, so the next login fetches a fresh one. Logins for that target then
  refetch a token every time until an operator fixes the provisioning. That is
  deliberate: it surfaces the misconfiguration instead of caching a token that
  cannot work.
- There is no retry and no buffering. A single timeout or 5xx response fails
  that login.
- Synchronization is fail-closed: a receiver failure blocks the login. This is
  an availability trade-off and not a security control.
- Only LOGIN is supported. REGISTER and UPDATE_PROFILE are out of scope.
- The receiver ownership, authorization details, and wider integration contract
  remain undecided, so the version remains `0.x`.
- The payload carries only `event_type`, `username` and `groups` (PermissionSync
  ADR-0001) and has no event, request, correlation, or idempotency identifier,
  so delivery is at-most-once. The receiver cannot deduplicate on payload
  equality, and Keycloak-side and receiver-side logs cannot be reliably
  correlated during an incident.

## Layout

- `src/main/java/` contains the provider implementation.
- `src/test/java/` contains unit tests, support code, and `LoginSyncIT`.
- `docs/` contains decisions, contracts, plans, and generated QA evidence.
- `scripts/test.sh` is the supported Maven entry point.
- `podman-compose.yml` defines the local two-service stack.
- `Makefile` provides the local development commands.

## Releasing

To release, update `<version>` in `pom.xml` and merge the change to `main`. The
workflow reads that version. If a GitHub Release for `vX.Y.Z` already exists,
the workflow does nothing. Otherwise it builds the jar, retains an existing tag
or creates a missing one, then creates the GitHub Release.
Keycloak itself is pinned by the `keycloak.version` property, which drives both
the compile dependencies and the container image the tests run against.

## License

This project is dual-licensed under Apache-2.0 (`LICENSE-APACHE`) or MIT
(`LICENSE-MIT`), at your option. See `SECURITY.md` for the security policy.
