### Spec Compliance

- ✅ Spec compliant for the frozen nine-product A8 change against BASE `1d1610278120b97dbfc0b5eb01e0466f141b6735`; no blocking missing, extra, or misunderstood product change found. All nine product SHA-256 values independently match the root gate. Reviewed package SHA-256: `c340880a4a59b5fcdb20da5fcd6d73ea33fa4036ad89dad0b775e3d3164a886e`.
- ✅ All three booking-label hunks are present and distinguish viewing from creation/change/cancellation: `b-app/src/i18n/locale/zh-CN.ts:2828`, `b-app/src/i18n/locale/en.ts:2520`, `b-app/src/i18n/locale/ar.ts:2518`. Chinese matches authority at `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java:152`; the constants remain `biz:booking:view` / `biz:booking:manage` at lines 103/105.
- ✅ Missing `common.delete` is added only in English/Arabic (`b-app/src/i18n/locale/en.ts:45`, `b-app/src/i18n/locale/ar.ts:45`). Exactly one Chinese `mySpecs.delete` is added beside save/cancel (`b-app/src/i18n/locale/zh-CN.ts:341`), as expressly authorized by the brief's root ruling; existing nested entries are preserved.
- ✅ Stock messages preserve remediation and show current `{0}` / conflicting `{1}`: `backend/shop-app/src/main/resources/i18n/messages.properties:263`, `messages_en.properties:262`, `messages_ar.properties:271`. The original `{1}` was already displayed; only `{0}` was omitted.
- ✅ Java change is limited to two imports and the adjacent existing call (`backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java:3`, `:158`); `storeNo, other` is unchanged. The focused unchanged-producer check confirms the same order at `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryWritebackService.java:159`; error mapping is unchanged at `backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java:963`.
- ✅ Required validation was executed and accurately reported, rather than skipped: shared final output is 24/24 (`/tmp/task-60a-a8-final-green.log:5`), actual XML is 6 tests / 0 failures / 0 errors / 0 skipped, and typecheck exits 2 (`.superpowers/sdd/beauty-backend/task-60a-a8-report.md:134`). This is not overall completion: the frontend gate remains OPEN pending mandatory A8b.
- ⚠️ Cannot verify from diff: unchanged permission-grant runtime behavior and production Java MessageFormat rendering. A8 adds no grants or runtime formatting implementation; its new test explicitly limits itself to a source/slot contract (`packages/shared/tests/stock-shared-source-label.test.ts:41`). No deployment or end-to-end claim is supported or needed for this scoped verdict.

### Strengths

- `packages/shared/tests/biz-perm-label.test.ts:58` routes real locale loading through the strengthened duplicate-rejecting parser, and `:89` tests duplicate injection into actual locale source rather than an unrelated substitute parser. Booking assertions at `:78` remain focused on the newly required labels.
- `packages/shared/tests/stock-shared-source-label.test.ts:10` checks unique actual properties entries; `:17` reads both real Java producers; `:28` locks the supplied argument order; `:34` requires exactly placeholder indices `[0,1]`. Distinguishable samples at `:38` detect reversed argument mapping without claiming to implement Java MessageFormat.
- `/tmp/task-60a-a8-red.log:23` and `:185` show behavioral failures for missing booking labels and `{0}`. `/tmp/task-60a-a8-mutation-placeholder.log:10` detects the removed current-store slot; `/tmp/task-60a-a8-mutation-argument-order.log:13` detects the actual reversed Java arguments. Restoration hashes are recorded at `.superpowers/sdd/beauty-backend/task-60a-a8-report.md:64`, and current hashes independently match the frozen products.
- `.superpowers/sdd/beauty-backend/task-60a-a8-report.md:126` accurately keeps the frontend failure visible, attributes it to unchanged sources, and avoids a full-project passing claim.

### Issues

#### Critical (Must Fix)

- None in the frozen A8 products.

#### Important (Should Fix)

- **Open baseline validation blocker, not introduced by A8:** `b-app/src/api/endpoints.ts:286` and `b-app/src/api/mocks/marketing.ts:34` fail typechecking because `mResolveMemberCode` is missing from `MerchantApi` (`b-app/src/api/contract.ts:843`). Actual diagnostics remain in `/tmp/task-60a-a8-typecheck-final.log:1`. Root's gate identifies all three sources as BASE-identical, and the A8 brief explicitly requires reporting unrelated failures without broad fixes. Complete the separately authorized A8b contract/adapter repair and obtain a passing frontend typecheck before overall acceptance; A8's scoped approval does not waive this gate.

#### Minor (Nice to Have)

- **Duplicate coverage boundary:** `packages/shared/tests/biz-perm-label.test.ts:66` retains the pre-existing `biz:`-only regex, so the new rejection at `:68` protects parsed `biz:` entries but does not detect duplicates of the visible `pay:cash:receive` / `trade:checkout:prepare` entries (`b-app/src/i18n/locale/zh-CN.ts:2834`). This does not undermine the new booking coverage and is not an A8 regression. Document this boundary when describing the guard; if universal permission-entry duplicate protection is desired, extend its parsing and focused tests in an explicitly scoped follow-up.
- **Existing compiler-output noise:** `/tmp/task-60a-a8-backend.log:535` reports implicit annotation processing, `:542` deprecated APIs in DevSeeder, `:558` deprecated test APIs, and `:560` unchecked/unsafe test operations. These are disclosed in `.superpowers/sdd/beauty-backend/task-60a-a8-report.md:124`, with no `[WARNING]` lines and no A8 source change in the implicated files. Preserve visibility and address under separate maintenance; they do not invalidate the 6/0/0/0 XML result.

### Assessment

**Task quality:** Approved for the frozen A8 products; overall frontend validation remains OPEN.

**Reasoning:** The implementation is small, preserves the production call contract, and adds focused source-contract tests backed by genuine RED and mutation evidence. No A8 product fix is required for approval; mandatory A8b is still required to close the independently failing frontend gate.

**Checks performed:** Read the supplied brief, report narrative, root evidence gate, and full independent diff. The initial combined tool output truncated the diff's test tail, so the remaining package tail was read; no changed source file was reread. Independently checked the package hash and all nine current product hashes, inspected existing RED/mutation/final-GREEN log evidence, and parsed the actual backend XML (SHA-256 `38e230e0da13474493312b82c990448bf91ce904dfb8136a385caed236594879`).

**Named outside-diff risk/check:** Cross-source semantic drift between frontend booking labels and backend authority, and between stock message slots and the unchanged second producer: focused `rg` checks of BizPerms booking constants/labels, ErrorCode.STOCK_SHARED_SOURCE, and InventoryWritebackService's shared-source call only. No suite was rerun; no source, index, HEAD, or branch mutation was performed.
