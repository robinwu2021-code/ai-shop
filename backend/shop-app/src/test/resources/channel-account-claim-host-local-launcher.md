# N2 host loopback launcher

Test main `ai.neargo.shop.commercebridge.ChannelAccountClaimHostLocalLauncher` with
`-Dchannel.account.claim.host.local.acceptance=true`. Root runs compilation and the
local runner; this launcher never invokes Maven or accesses a remote database.

| Environment | Contract |
| --- | --- |
| `COMMERCE_MYSQL_URL` | Admin URL `jdbc:mysql://127.0.0.1:3308/?...` |
| `COMMERCE_MYSQL_USER`, `COMMERCE_MYSQL_PASSWORD` | Disposable host DB credentials |
| `ALIGNMENT_PAY_ORIGIN` | Fixed loopback PAY origin, reserved before PAY starts |
| `ALIGNMENT_INTERNAL_TOKEN`, `ALIGNMENT_PAY_INTERNAL_CREDENTIAL` | Same private values as PAY, at least 32 characters |
| `N2_HOST_READY_FILE` | Absolute private JSON path; parent directory exists |
| `N2_HOST_PORT` | Host port, default `0` |
| `N2_HOST_SCHEMA` | Optional existing `commerce_test_<32 hex>` schema for restart |
| `N2_OWNER_PASSWORD` | Optional on fresh start, required unchanged on restart |
| `N2_OPS_TOKEN` | On restart pass the previous private OPS token to retain the same DB session; invalid/revoked token fails |

A fresh start creates a disposable host schema. Restart reuses it without deleting
claims, source facts or sessions. The runner drops the schema only after stopping
both processes and collecting nonsensitive evidence.

Ready JSON is atomically written with mode `0600`: `hostOrigin`, `schema`,
`principal`, `password`, `ownerRef=U`, `sourceEntityNo=M`, `storeNo=S`, `sourceScope`,
`sourceCaller`, `opsToken`, `opsSessionKind`, `databasePreserved`. Do not print this
file or put credentials/tokens in reports. Output only marks `N2_HOST_LOCAL_READY`.

The merchant must log in through the real password and unified-identity APIs; no
BIZ token is seeded. PAY delegates explicitly to typed `USER U MERCHANT_ADMIN` via
its own launcher, with `N2_SOURCE_ENTITY_REF=M` and `ALIGNMENT_HOST_ORIGIN` from this
ready file. Both host BIZ feature flags are true only inside this test launcher.
N1's existing launcher and production default flags retain their behavior.

OPS is **a pre-provisioned controlled DB session, not a real password journey**.
It uses real `SessionProfiles.OPERATOR`, `OperatorIdentityLoader`, production OPS
security chain and actual `sys_ops_staff/sys_role/sys_role_point/sys_function_point`
rows. The fixture grants only channel-account view/manage/approve permissions, not
an ApprovalGate bypass. `/ops/auth/me` projects the authenticated operator and
accepts only the explicitly unrestricted local fixture scope; it never maps a
fixed token to an identity. Existing account verify/activate and separation of
approval duties remain mandatory and are outside this launcher's grant.
