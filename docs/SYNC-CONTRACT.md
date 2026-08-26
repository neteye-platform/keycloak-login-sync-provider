# External Syncservice Contract

This document records the contract the Keycloak `login-sync` Authenticator depends on. The
plugin is the *caller*. Everything on the receiver side is an assumption, not something this
repository builds. Nothing here is receiver-side code, and no receiver behaviour is implemented
by this project.

Authority: `LLD.pdf` sections 3.3, 3.4, 4.4 and 5, plus decision R5 in
[plans/0001-contract-reconciliation.md](plans/0001-contract-reconciliation.md).

## Request

The plugin performs a single HTTP call per logical sync:

```text
POST {service-endpoint}
Authorization: Bearer <service-account-jwt>
Content-Type: application/json
```

`{service-endpoint}` is the configured complete receiver URL, including its path (for example
`http://receiver:8081/api/sync-user`). The config property is `service-endpoint` (env
`KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT`). LLD 4.3.1 spells the same property
`endpoint`; that difference is flagged for alignment by the LLD owner, not adopted here. The
provider posts to this URL verbatim, consistently with the complete `sa-token-endpoint` URL.

Upgrading from an earlier version is a breaking configuration change: an existing base-only URL
must be updated to include the receiver path, or the provider will post to that base URL instead.

The bearer token authenticates the **technical caller**. It is a service-account JWT obtained
through OAuth2 Client Credentials. It is deliberately **not the logging-in user's** token, and
the plugin never constructs, forges or re-signs a token of any kind. It only forwards a token
issued to it.

## Body

The body carries exactly the six fields fixed by LLD section 4.4. No more, no fewer.

| field        | type            | meaning                                            |
| ------------ | --------------- | -------------------------------------------------- |
| `event_type` | string          | always the constant `LOGIN`                        |
| `client_id`  | string          | the OIDC client the user authenticated against     |
| `username`   | string          | the Keycloak username                              |
| `email`      | string or null  | the user's email, null when unset                  |
| `groups`     | array of string | full group paths, for example `/staff/engineering` |
| `timestamp`  | string          | ISO-8601 UTC, truncated to whole seconds           |

Example:

```json
{
  "event_type": "LOGIN",
  "client_id": "internal-portal",
  "username": "jdoe",
  "email": "jdoe@example.com",
  "groups": ["/staff", "/staff/engineering"],
  "timestamp": "2026-08-24T09:15:32Z"
}
```

## Responses

| outcome           | plugin interpretation                       |
| ----------------- | ------------------------------------------- |
| `200 OK`          | success                                     |
| `201 Created`     | success                                     |
| `400 Bad Request` | single-attempt failure, no second POST      |
| `401`             | single-attempt failure, no second POST      |
| `403`             | single-attempt failure, no second POST      |
| `500`             | single-attempt failure, no second POST      |
| timeout           | single-attempt failure, no second POST      |
| IO error          | single-attempt failure, no second POST      |

Every non-success outcome is terminal for that login. There is exactly one HTTP attempt per
logical sync, and an admitted failure blocks the login rather than permitting it.

## Receiver-side assumptions (NOT IMPLEMENTED here)

These are the checks the receiver is **assumed** to perform. This repository implements none of
them and provides no stub, scaffold or reference for them.

- Verify the token signature against Keycloak's cached `JWKS` public keys.
- Check `exp` so expired tokens are rejected.
- Check the expected `iss` matches the trusted Keycloak realm issuer.
- Check the expected `aud`, if an audience is configured for the integration.
- Accept only an explicitly allowed signing algorithm.
- Require a `least-privilege` technical role or scope, so the caller can do this and nothing else.

If the receiver skips these, the plugin cannot compensate. The plugin sends the token; it never
validates it on the receiver's behalf.

## Open integration decisions

- The exact technical role name is `NOT decided` and must be finalized before a real deployment.
- The exact claim carrying that role is `NOT decided` and must be finalized before a real
  deployment.
- The audience value is `NOT decided` and must be finalized before a real deployment.
- LLD Open point 3, ownership of the receiver, is still open. Until an owner exists, no counterpart
  can agree to a contract revision.
- `/api/sync-user` is the default/reference receiver path documented here, not a provider-owned
  constant. An operator may configure any path through the complete `service-endpoint` URL. The
  undecided ownership and authorization items above, rather than this configurable path, are why
  the project stays at `0.x`.

## Delivery semantics (decision R5)

The payload carries **no event id, no request id, no correlation id and no idempotency key**, and
`timestamp` is truncated to whole seconds. The consequences are concrete and are stated here so
nobody discovers them in production:

- Two genuine logins by the same user to the same client within one second produce
  **byte-identical** bodies.
- The receiver **cannot** distinguish a duplicate delivery from two real logins, and therefore
  **must not** deduplicate on payload equality. Doing so would silently discard real login events.
- Delivery is **at-most-once**. A failed or skipped sync is never replayed, so the receiver may
  simply never hear about a login that happened.
- An operator **cannot** reliably correlate a Keycloak-side log line with a receiver-side log line.
  Investigations have to fall back on username, client and second-resolution time.

Two rules follow from this:

1. The payload MUST NOT be extended without a contract revision agreed with the receiver owner.
   The six fields are fixed by the LLD.
2. If correlation later becomes necessary, the agreed mechanism should be a **transport header**
   rather than a body field, so the LLD-fixed body stays intact.
