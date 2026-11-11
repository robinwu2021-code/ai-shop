# TW-E2E-20261110-01

1. Retain the deployed V649/V650 migrations. Rebase the pending brand legal binding to V651 and ordinary brand promotion to V652. Rehearse the legal slice against the existing 166 schema and run the ownership, replay and authorization tests before publishing.
2. Query candidates as the controlled brand manager. Bind only an ACTIVE legal entity confirmed by the candidate API and read back the original command. Bring up brand marketing with its verified dependencies.
3. Restore a FOOD/TW activity preset from source and verify DRAFT creation and scoped readback. Preserve the existing publication gate.
4. Configure FOOD test SKU through operator catalog APIs. Verify manual collection with synthetic proof, fulfillment and entitlement readback using an authorized test operator.

## 166 verification

- Application source commit `58ced2010`, running API and OPS JAR SHA-256 `a6ebbaaed9e4eabef30decda32b40d3bcbd1e927b46178986dade4760ba85d6c`. Both health checks returned UP. The release lock was released.
- 25 focused tests passed, including three native MySQL legal binding tests and the account-route and marketing HTTP contract checks. The 166 schema backup rehearsal applied V651 and V652. Both applied successfully in 166 with V649/V650 retained. The rehearsal schema and temporary migration identity were removed.
- API response from the authorized test merchant for `BR261110Z2FRZ2DHE5/legal-entity-candidates` returned an empty array. The brand is active and unbound. Marketing context with `MGS26111035M1KP1S2T` returned business code 10409. Store `ST2611100C2XNFCXRS` remains bound under `BRC20261111000001` and its entity `M261106BF7GK8052W` remains PENDING_LICENSE.
- Inserted the canonical AP023 version 1 draft template from V483 under audit marker TW166-DRAFT-FIXTURE. The FOOD preset endpoint returned AP023/v1 with enabled=false and available=false. The scoped TWD draft attempt returned 10404. Scope validation requires the store's entity to be ACTIVE. Draft authoring also has a deployment opt-in. Publication was outside this acceptance.
- FOOD PRO and CHAIN remain OFF_SALE. The authorized operator needs commerce:catalog:read/edit/publish and commerce:mall:offline:evidence/confirm plus commerce:mall:order:read for readback. The designated 166 automation identities tested carry NearPay roles and do not have the required permissions. FOOD industry publication would affect the shared FOOD catalog. No FOOD order or synthetic ledger entry was created.
- Reusable backups are at `/data0/app/ai-shop/releases/tw166-chain-20261111` and `/data0/mysql9/backups/tw166-chain-20261111`.

## Scoped draft candidate

The draft path now supports a deployment scope configured under `shop.promotion.draft-scope`. For this acceptance configure tenant-no MAIN, entity-no M261106BF7GK8052W, store-no ST2611100C2XNFCXRS, template-code AP023 and currency TWD. The existing merchant permission, active entity check and selected store ownership validation run before draft admission. The existing global activity flag remains required for publishing and status changes. Default configuration keeps the scoped path closed. This candidate awaits formal activation of the test entity for 166 acceptance.
