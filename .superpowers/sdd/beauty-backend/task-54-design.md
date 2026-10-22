# Task 54 design

Tier 2, existing ADR045 and Task54 brief are authoritative. Controller owns main TDD/progress.

## Scope and acceptance

Shared guest identity belongs to member, neutral contracts to shop-base/spi/member. No beauty guest, buyer, account, member or order creation. Existing member tickets supply cr1 claims and mc1 consumer proof; identity never grants payment authority.

## Implementation

- GuestRegistrationPort exact brief records and operations; GuestRegistrationInternalApi typed envelopes echo identity, scope and command/query.
- MbrGuestRegistration/BaseEntity and MemberGuestRegistrationMapper; append V537 after checking maximum V536. One unique original registration key per tenant/entity/store/actor realm/kind and guest business key.
- MemberGuestRegistrationServiceImpl preflights complete SecurityUtils identity, MERCHANT realm, current CUSTOMER access and real store facts outside its short local transaction. Original actor namespace binds registration replay only. Current authorized same-store staff may get/verify/claim with their own cr1; no impersonation of the original actor. Register stores request/key hashes and encrypted contact/claim credential; original key replays decrypt the persisted credential. Concurrent duplicate register catches database unique conflict then resolves committed replay.
- Claim preflights actual actor/store-bound cr1 using MemberCodeResolveService, locks guest root FOR UPDATE, verifies random credential and fixes exactly one real active user. Claimed key/hash/result live in that root row, without generic idempotency JSON. Same key/same command replays, changed command or different user conflicts.
- MemberGuestCrypto uses AES-GCM, random 12-byte IV and explicit tenant/entity/store/guest/purpose AAD, 32-byte base64 deployment key with no default. Missing/invalid key returns MEMBER_GUEST_UNAVAILABLE 40042 / 503 for this capability only.
- Existing functional CustomerIdentityPort gains default fail-closed consumer proof. Real member service checks current CONSUMER/USR tenant/user, original unexpired MEMBER_CODE, and active user. No login-token fallback.
- Internal controllers validate service token before delegated identity, restore context in finally, return real statuses under global advice. Beauty remote adapters validate complete response echoes and nonempty facts; only server owner configuration wires the remote guest port.

## Evidence plan

Behavioral RED with compilable contract/stub before owner implementation. MemberGuestRegistrationIT exercises real database and real owner permission facts on H2 and native MySQL8.4: registration replay/change/namespace/store/actor/revocation, encryption/restart/tamper/no account/order side effects, claims/concurrent unique winner and rollback, genuine mc1 proof/expiry/other/forged. TCP tests delimit real transport/global advice from owner facts and cover bad token, namespace echo, malformed/error200 rejection. Native runner owns one cached --pull never MySQL8.4 tmpfs container on localhost and finally removes only its container; current schema fixture plus formal V537, never full historical replay. Run architecture/i18n/SQL and beauty reactor/assembly regressions after shared install. Remove identity or claim constraint to obtain failing counterexample, restore and rerun. Report exact files/tests/logs and boundaries.


## Confirmed security increments after first GREEN

V537 remains frozen at SHA256 9c93c44dc328b4cbf47be8baae6972743e0922aca1a0cf62b84906905b4f0b0f. A separate V538 adds nullable actor_realm/actor_kind to the original member identity ticket. New authenticated reference issuance binds the actual MERCHANT identity, while legacy no-namespace references remain readable through the existing compatibility API. New requireGuestClaimReference is stricter: current complete namespace, actor/store reference binding and active user must all match; old namespace-less tickets fail closed for guest claims. Same actor number in MCH and USR is explicitly tested with both holding current CUSTOMER permission.

Registration payload fingerprints use HmacSHA256 with the explicit owner secret and a separate member-guest-registration-request-v1 domain plus length-framed scope/actor/contact/name/consent. Unkeyed contact SHA256 would be a low-entropy offline guessing oracle. Random guest-secret hashes and idempotency key hashes remain SHA256. Tests compare actual stored request hashes under two owner keys, same-key replay across a fresh owner/JVM and changed-contact conflict, without deriving expected values using implementation helpers. No secret/key or raw contact is logged.


## Committed claim replay

Exact original key/request hash/full claiming namespace plus the guest secret returns the immutable stored Claim even after the original cr1 expires. Current CUSTOMER access is still checked every time. For an already claimed observation, active-user facts are read outside root locks, then user and revision are matched again under the guest root lock. New claims still require a live strict namespace-bound cr1. Changed key/parameters or actor namespace conflict; replay cannot rebind. Native tests cover reference expiry, disabled user, revoked operator and same-number different namespace. No RPC occurs under the guest lock.
