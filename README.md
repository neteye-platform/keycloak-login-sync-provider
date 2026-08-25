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
  Scope key `service-endpoint`. Required, no default. Base URL of the receiver;
  the plugin appends `/api/sync-user`.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_ID`
  Scope key `sa-client-id`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_SECRET`
  Scope key `sa-client-secret`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_TOKEN_ENDPOINT`
  Scope key `sa-token-endpoint`. Required, no default.
- `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__HTTP_TIMEOUT_MS`
  Scope key `http-timeout-ms`. Optional, with a default of `5000` milliseconds.

A missing required value degrades the provider to a no-op that permits logins.
A malformed URL or a non-numeric or non-positive timeout deliberately aborts
provider startup with an `IllegalStateException`. The bulkhead limit and token
timeouts are internal constants and are deliberately not operator-facing.

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

The exact least-privilege role name, the claim carrying it, and the audience
are open integration decisions that must be finalized before real deployment.
See "Open integration decisions" in
[the sync contract](docs/SYNC-CONTRACT.md).

Deploy the built JAR into `/opt/keycloak/providers`.

## Limitations

- The service-account token cache is per-JVM, therefore per-node in a cluster,
  and is shared across all realms for one service endpoint.
- There is no retry and no buffering. A single timeout or 5xx response fails
  that login.
- Synchronization is fail-closed: a receiver failure blocks the login. This is
  an availability trade-off and not a security control.
- Only LOGIN is supported. REGISTER and UPDATE_PROFILE are out of scope.
- `/api/sync-user` is provisional, which is why the version remains `0.x`.
- The payload has no event, request, correlation, or idempotency identifier.
  Its timestamp is truncated to seconds, so delivery is at-most-once. The
  receiver cannot deduplicate on payload equality, and Keycloak-side and
  receiver-side logs cannot be reliably correlated during an incident.

## Layout

- `src/main/java/` contains the provider implementation.
- `src/test/java/` contains unit tests, support code, and `LoginSyncIT`.
- `docs/` contains decisions, contracts, plans, and generated QA evidence.
- `scripts/test.sh` is the supported Maven entry point.
- `podman-compose.yml` defines the local two-service stack.
- `Makefile` provides the local development commands.

## Releasing

The release workflow resolves the POM version's `v<version>` Release. An
existing Release is a no-op. When absent, the workflow builds the POM version's
JAR, retains an existing tag or creates an absent one, and then creates the
Release.

The `workflow_dispatch` trigger is active, while the `push` trigger is
currently commented out. Do not change release triggers, pinned action
revisions, artifact naming, or token permissions without an explicit
release-workflow change.

## License

This project is dual-licensed under Apache-2.0 (`LICENSE-APACHE`) or MIT
(`LICENSE-MIT`), at your option. See `SECURITY.md` for the security policy.
