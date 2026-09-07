# External Syncservice Contract

This document records the contract the Keycloak `login-sync` Authenticator depends on. The
plugin is the *caller*. Everything on the receiver side is an assumption, not something this
repository builds. Nothing here is receiver-side code, and no receiver behaviour is implemented
by this project.

Authority: `LLD.pdf` sections 3.3, 3.4, 4.4 and 5, plus decision R5 in
[plans/0001-contract-reconciliation.md](plans/0001-contract-reconciliation.md). Where PermissionSync
has a governing ADR, that ADR is the authoritative receiver-side contract and this document
mirrors it:
[0001-inbound-synchronization-contract](https://github.com/neteye-platform/permissionsync/blob/main/docs/adr/0001-inbound-synchronization-contract.md),
[0002-receiver-side-jwt-verification](https://github.com/.../0002-...),
[0003-at-most-once-delivery-and-idempotent-reconciliation](https://github.com/.../0003-...).

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

Plain-HTTP `http://` endpoints are accepted only when the development-only `allow-insecure-http`
opt-in (`KC_SPI_AUTHENTICATOR__LOGIN_SYNC__ALLOW_INSECURE_HTTP=true`) is set. In production both
credential-bearing endpoints must use HTTPS; see the configuration section of the README.

Upgrading from an earlier version is a breaking configuration change: an existing base-only URL
must be updated to include the receiver path, or the provider will post to that base URL instead.

The bearer token authenticates the **technical caller**. It is a service-account JWT obtained
through OAuth2 Client Credentials. It is deliberately **not the logging-in user's** token, and
the plugin never constructs, forges or re-signs a token of any kind. It only forwards a token
issued to it.

## Scope

The plugin does not accept whatever scopes the service-account client happens to carry. It asks
for one, explicitly. The target is the `clientId` of the client the user is logging in to, and the
token request to `sa-token-endpoint` carries `scope=permissionsync:<clientId>` as a URL-encoded
form parameter in its `application/x-www-form-urlencoded` body, alongside `grant_type`,
`client_id` and `client_secret`. The issued JWT carries that scope, and the receiver routes and
authorizes on it. The body is untouched by this: it stays at exactly the three ADR-0001 fields,
and the target never appears in it.

The service-account token cache is keyed by scope. One cached token per `permissionsync:<target>`,
each with its own refresh, so tokens for two targets are never interchanged. A `401` or `403`
invalidates only the scope that saw it.

For each target an instance serves, the operator must assign the client scope
`permissionsync:<clientId>` to the service-account client as **Optional**, never Default, and must
also provision the PermissionSync audience. A Default scope is applied to every token
unconditionally, so a service account serving two targets would emit two `permissionsync:*` scope
tokens at once, which ADR-0002 answers with `403`. An Optional scope is included only when
requested, which is what the plugin does. The reverse mistake is silent: Keycloak drops a requested
scope that is not assigned to the client without raising an error, leaving a token with zero
`permissionsync:` scope tokens that the contract also answers with `403`.

## Body

The body carries exactly the three fields fixed by PermissionSync ADR-0001. No more, no fewer:
that receiver rejects unknown fields with `400`, so `client_id`, `email` and `timestamp` (present
in earlier LLD revisions) MUST NOT be serialized.

| field        | type            | meaning                                            |
| ------------ | --------------- | -------------------------------------------------- |
| `event_type` | string          | always the constant `LOGIN`                        |
| `username`   | string          | the canonical user key the receiver resolves       |
| `groups`     | array of string | full group paths, for example `/staff/engineering` |

Example:

```json
{
  "event_type": "LOGIN",
  "username": "jdoe",
  "groups": ["/staff", "/staff/engineering"]
}
```

## Responses

| outcome           | plugin interpretation                              |
| ----------------- | -------------------------------------------------- |
| `200 OK`          | success: the target changed to the desired state   |
| `204 No Content`  | success: the target already had the desired state  |
| `400 Bad Request` | single-attempt failure, no second POST             |
| `401`             | single-attempt failure, no second POST             |
| `403`             | single-attempt failure, no second POST             |
| `500`             | single-attempt failure, no second POST             |
| timeout           | single-attempt failure, no second POST             |
| IO error          | single-attempt failure, no second POST             |

Every non-success outcome is terminal for that login. There is exactly one HTTP attempt per
logical sync, and an admitted failure blocks the login rather than permitting it.

## Receiver-side assumptions (NOT IMPLEMENTED here)

These are the checks the receiver is **assumed** to perform. PermissionSync ADR-0002 fixes them;
this repository implements none of them and provides no stub, scaffold or reference for them.

- Verify the token signature against Keycloak's cached `JWKS` public keys.
- Check `exp` (and `iat`) so expired tokens are rejected.
- Check the expected `iss` matches the trusted Keycloak realm issuer.
- Check `aud` contains the configured PermissionSync audience.
- Check the `client_id` claim, emitted by the service-account client-id mapper.
- Require the `scope` claim to contain **exactly one** exact token with the
  `permissionsync:<target>` prefix; the suffix is the logical target the caller may touch.

If the receiver skips these, the plugin cannot compensate. The plugin sends the token; it never
validates it on the receiver's behalf.

## Open integration decisions

What was previously open is now decided by PermissionSync:

- **Authorization** is decided by ADR-0002: the token must carry exactly one `permissionsync:<target>`
  scope token, the PermissionSync audience, and `client_id`. Provisioning that scope/audience on the
  service-account client is an operator task, not provider code; the plugin deliberately does not
  hardcode a target because it varies per deployment. It requests the target derived from the login
  client instead, which is a safe identifier only inside one trust domain: two realms can each hold a
  client of the same name, and it is the provider's restriction to its own realm that keeps the name
  unambiguous.
- **Ownership**: PermissionSync (the receiver) is the contract owner. `/api/sync-user` is its
  documented path and the reference value used here; an operator may still configure any path
  through the complete `service-endpoint` URL.

## Delivery semantics (decision R5)

The payload carries **no event id, no request id, no correlation id, no idempotency key and no
timestamp**. PermissionSync ADR-0003 mirrors this: single-attempt, at-most-once, no retry, no
deduplication on the receiver. The consequences are concrete and are stated here so nobody
discovers them in production:

- Two genuine logins by the same user with identical groups produce **byte-identical** bodies.
- The receiver **cannot** distinguish a duplicate delivery from two real logins, and therefore
  **must not** deduplicate on payload equality. Doing so would silently discard real login events.
- Delivery is **at-most-once**. A failed or skipped sync is never replayed, so the receiver may
  simply never hear about a login that happened.
- An operator **cannot** reliably correlate a Keycloak-side log line with a receiver-side log line.
  Investigations have to fall back on username, client and second-resolution time.

Two rules follow from this:

1. The payload MUST NOT be extended without a contract revision agreed with the receiver owner.
   The three fields are fixed by PermissionSync ADR-0001.
2. If correlation later becomes necessary, the agreed mechanism should be a **transport header**
   rather than a body field, so the ADR-fixed body stays intact.
