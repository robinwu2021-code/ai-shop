# Task60A-A8 implementation report

Status: IMPLEMENTED, focused shared tests and backend guard GREEN; frontend typecheck remains RED on two independently existing MerchantApi errors. Ready for root's fresh independent spec/quality review; no product commit made.

## Identity and exact scope

- Worktree: `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`
- Branch: `codex/beauty-foundation`
- Implementation BASE and current HEAD: `1d1610278120b97dbfc0b5eb01e0466f141b6735`
- Product SHA: no new commit; the exact uncommitted product blobs/SHA-256 and complete BASE-to-current diff appear below. Eight modified files and one new file, exactly nine products.
- Spec: `.superpowers/sdd/beauty-backend/task-60a-a8-brief.md`, including root's final bounded parity ruling. Tier 1; existing TDD main §2.32 was released by root before coding. Root docs/ledger were not edited by this implementer.
- No subagents, staging, commit, push, merge, dependency installation, lockfile update, or deployment.

## Result and requirements

The three real b-app `perm` objects now have both `biz:booking:view` and `biz:booking:manage`. Chinese strings match `BizPerms.LABELS` exactly: `查看预约` / `创建、调整及取消预约`. English: `View appointments` / `Create, change and cancel appointments`; Arabic: `عرض المواعيد` / `إنشاء المواعيد وتعديلها وإلغاؤها`. View and management remain separate permissions; no grant, owner, endpoint, schema, or permission-code change.

Before edits the three `perm` blocks each had 15 quoted permission entries and no duplicate keys. Existing Chinese `common.delete` was present; English/Arabic lacked it. Added only missing English `Delete` and Arabic `حذف` at `common.delete`, preserving their existing `mySpecs.delete` entries.

The existing permission-label parser now rejects repeated parsed `biz:` permission entries instead of silently overwriting them. Its real locale-loading path calls that parser; duplicate tests inject an actual existing `biz:receive` entry into each actual locale source and require rejection. Focused assertions cover the six booking labels without copying every pre-existing permission label. Existing parity/placeholder checkers remain byte-identical to BASE.

All three `err.inventory.shared_source` entries now identify current store `{0}` and conflicting store `{1}`, retaining independent stock-location remediation. The old messages ALREADY displayed conflicting store `{1}`; they omitted only the current store slot. Both producers still supply `storeNo, other` in the original order. `StockReconcileService` only adds normal `BizException`/`ErrorCode` imports and shortens its adjacent existing call; `InventoryWritebackService` is read-only and byte-identical to BASE.

The new stock test reads the actual three property entries (one unique key per file), requires exactly the placeholder index set `[0,1]`, reads both actual Java producers, asserts `storeNo`/`other` ordering, and checks distinguishable `CURRENT-STORE-17` and `CONFLICTING-STORE-92` values against each message's slots. The literal slot projection is a focused static contract/display-coverage check, explicitly NOT a Java MessageFormat implementation or production runtime validation. No Java test file was changed. The existing `InventorySharedSourceTest` was read only; its exception assertion checks the error name, not the two args.

## Root ruling, precisely bounded

Initial post-change combined attempt: **23/24 passed**. Its sole failure was the pre-existing `mySpecs.delete` mismatch: BASE English/Arabic line 264 had `delete` in their `mySpecs` objects while BASE Chinese line 341's save/cancel row lacked it. Initial RED's missing `common.delete` assertion had masked this extra-key assertion. Root authorized adding exactly `mySpecs.delete="删除"` beside Chinese save/cancel siblings in the same owned locale, preserving English/Arabic entries, pages, and guards. Root's first wording named `goods.mySpecs`, then actual object evaluation confirmed `mySpecs` is top-level and root corrected brief/main/ledger. The implementation uses the actual top-level `mySpecs` object. Cost if wrong, per root: one unused translated key, preferable to removing an existing locale contract. No further parity repair was made.

## Actual RED → GREEN evidence

Working directory for shared runs: `packages/shared`.

```sh
../../node_modules/.bin/vitest run tests/biz-perm-label.test.ts tests/i18n-parity.test.ts tests/message-placeholder.test.ts tests/stock-shared-source-label.test.ts --cache false
```

1. First un-escalated attempt failed with Vite `EPERM` writing `.vite-temp` in the assigned worktree. This was setup, NOT behavioral RED. Re-ran the exact command with filesystem escalation; no install/config change.
2. Genuine RED before all production edits: exit 1, **12 failed / 12 passed, four files failed**, Vitest 4.1.11. Three focused booking tests received `undefined`; three existing permission coverage tests missed both booking codes; Chinese label parity missed those two labels; b-app parity missed `common.delete`; reverse message-placeholder guard reported the two stock call sites; three new stock-message tests received placeholder indices `[1]` instead of `[0,1]`. Existing duplicate checks and both producer-order assertions passed. The generic existing reverse checker diagnostic mentions swallowed parameters, but the exact A8 defect was omission of `{0}` only, not loss of `{1}`.
3. Minimal seven production-file edits: booking labels, missing common.delete, stock translations, and adjacent Java import/call cleanup. First combined attempt: exit 1, **23 passed / 1 failed**, solely the pre-existing `mySpecs.delete` mismatch described above.
4. Required mutations produced genuine assertion failures and exact source restoration (below).
5. Applied root's single-key Chinese ruling. Final exact four-file command after mutation restoration: exit 0, **4 files passed, 24 tests passed**, start `2026-10-25 14:45:04 +08:00`, duration 1.10s. No skip/allowlist/baseline change. No repeated unchanged shared suite after this final GREEN.

Final output:

```text
RUN  v4.1.11 /Users/robin/.codex/worktrees/beauty-data/ai-shop-codex/packages/shared


 Test Files  4 passed (4)
      Tests  24 passed (24)
   Start at  14:45:04
   Duration  1.10s (transform 86ms, setup 55ms, import 70ms, tests 979ms, environment 0ms)
```

## Required mutations and exact restoration

Both mutations ran only `../../node_modules/.bin/vitest run tests/stock-shared-source-label.test.ts --cache false` from `packages/shared`, each with source mutation and `finally` byte restoration in one Python process.

- Removed exactly the three bytes `{0}` from the actual English stock message (left surrounding whitespace, `{1}` and remediation intact): exit 1, **1 failed / 4 passed**. Failure: `messages_en.properties ... expected [1] to deeply equal [0,1]`.
- Swapped only the actual `StockReconcileService` call's `storeNo, other` to `other, storeNo`: exit 1, **4 failed / 1 passed**. Producer assertion received `['other','storeNo']`; the three locale/sample mapping assertions received reversed distinguishable values.
- Both files restored exactly to their pre-mutation bytes. The placeholder mutation was repeated with a strictly three-byte deletion to retain surrounding whitespace exactly; the final combined suite was re-run only to verify restoration after that precision refinement. Final combined GREEN ran only after restoration.

```json
[
  {
    "mutation": "placeholder",
    "file": "backend/shop-app/src/main/resources/i18n/messages_en.properties",
    "exit": 1,
    "original_sha256": "a09b30f8764f4e253c7cdfc93d9a8d5a520e387bdaca97a0ac47b35d8e8cb8fe",
    "restored_sha256": "a09b30f8764f4e253c7cdfc93d9a8d5a520e387bdaca97a0ac47b35d8e8cb8fe",
    "removed_exact_bytes": "{0}",
    "removed_byte_count": 3
  },
  {
    "mutation": "argument-order",
    "file": "backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java",
    "exit": 1,
    "original_sha256": "d8c3c21a0c820a9fbf8c0ee29c8a249fce90ddc5c75d675f6085403be98968cf",
    "restored_sha256": "d8c3c21a0c820a9fbf8c0ee29c8a249fce90ddc5c75d675f6085403be98968cf"
  }
]
```

## Backend validation, fresh actual XML

From worktree root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=BackendI18nParityTest -Dsurefire.failIfNoSpecifiedTests=false
```

Exit 0; all 21 reactor projects SUCCESS; shop-app actually ran `ai.neargo.shop.arch.BackendI18nParityTest`: **Tests run 6, Failures 0, Errors 0, Skipped 0**. Finished `2026-10-25T14:41:14+08:00`; total 20.394s. Fresh XML was written during this run, not inferred from a no-tests reactor success. The count is actually six tests, despite older project prose saying five. The mutations occurred after this build and exactly restored the Java/properties bytes validated by that build; the later single-key Chinese frontend change does not affect backend inputs.

Fresh XML metadata and actual method names:

```json
{
  "path": "backend/shop-app/target/surefire-reports/TEST-ai.neargo.shop.arch.BackendI18nParityTest.xml",
  "attrs": {
    "{http://www.w3.org/2001/XMLSchema-instance}noNamespaceSchemaLocation": "https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd",
    "version": "3.0.2",
    "name": "ai.neargo.shop.arch.BackendI18nParityTest",
    "time": "0.105",
    "tests": "6",
    "errors": "0",
    "skipped": "0",
    "failures": "0",
    "flakes": "0"
  },
  "sha256": "38e230e0da13474493312b82c990448bf91ce904dfb8136a385caed236594879",
  "mtimeUTC": "2026-10-25T06:41:13.995452+00:00",
  "testcases": [
    "noEmptyValues",
    "allLocalesHaveTheSameKeys",
    "everyMessageBelongsToAnErrorCode",
    "placeholdersMatchAcrossLocales",
    "everyErrorCodeHasAMessage",
    "noDuplicateKeys"
  ]
}
```

Compiler notices retained: annotation processing was auto-enabled and may be disabled in a future javac; existing `DevSeeder.java` uses deprecated APIs; existing `ActiveAddressFlowTest.java` and other tests use deprecated APIs; existing `PointsUserAdjustmentTest.java` uses unchecked/unsafe operations. Maven emitted no `[WARNING]` lines in the captured log; these compiler INFO notices are still disclosed. No warning-policy change.

## Frontend typecheck, precise existing-source attribution

From `b-app`, using the already installed root binary:

```sh
node ../node_modules/vue-tsc/bin/vue-tsc.js --noEmit
```

Exit **2**, initially and again against the final locale state. Actual final diagnostics:

```text
src/api/endpoints.ts(286,3): error TS2353: Object literal may only specify known properties, and 'mResolveMemberCode' does not exist in type 'Record<keyof MerchantApi, EndpointDef>'.
src/api/mocks/marketing.ts(34,3): error TS2344: Type '"mCampaignList" | "mSaveCampaign" | "mToggleCampaign" | "mCustomers" | "mMembers" | "mMemberStats" | "mMemberDetail" | "mEnrollMember" | "mPatchMember" | "mTagMembers" | "mBatchTagMembers" | ... 36 more ... | "mResolveMemberCode"' does not satisfy the constraint 'keyof MerchantApi'.
  Type '"mResolveMemberCode"' is not assignable to type 'keyof MerchantApi'.
```

Authority is `b-app/src/api/contract.ts:843`, `export interface MerchantApi`; that entire actual file has **zero occurrences** of `mResolveMemberCode`. `endpoints.ts:286` supplies that key to `Record<keyof MerchantApi, EndpointDef>`; `mocks/marketing.ts` includes it in the `Pick` union at line 41 (reported at 34:3), with its existing mock handler at line 83. The endpoint, mock and contract files are each byte-for-byte identical to implementation BASE; hashes are below. No MerchantApi/endpoints/mocks or frontend baseline repair was attempted, per root instruction. This is an explicit outstanding independent type-check gap, not a passing frontend claim.

The following also proves read-only producer/permission/error-code sources and unchanged shared guard files:

```json
[
  {
    "file": "b-app/src/api/endpoints.ts",
    "identical_to_BASE": true,
    "base_sha256": "2c63ca6b89049ad80c3b7ae142a1b3d5c277c170ef02c174fe1d67c0af9926e2",
    "current_sha256": "2c63ca6b89049ad80c3b7ae142a1b3d5c277c170ef02c174fe1d67c0af9926e2"
  },
  {
    "file": "b-app/src/api/mocks/marketing.ts",
    "identical_to_BASE": true,
    "base_sha256": "79b4f91632d843b570a0e89c158c43bfc5bd722d6beb5e2f8bda3be8da2cdb5b",
    "current_sha256": "79b4f91632d843b570a0e89c158c43bfc5bd722d6beb5e2f8bda3be8da2cdb5b"
  },
  {
    "file": "b-app/src/api/contract.ts",
    "identical_to_BASE": true,
    "base_sha256": "fd9b092128f04e4cb30d3655aeaf0200e95ef555d4ef47673c4c36d6a1147ad9",
    "current_sha256": "fd9b092128f04e4cb30d3655aeaf0200e95ef555d4ef47673c4c36d6a1147ad9"
  },
  {
    "file": "backend/shop-app/src/main/java/ai/neargo/shop/invbridge/InventoryWritebackService.java",
    "identical_to_BASE": true,
    "base_sha256": "688a7ee742d9456d56a55c04a3596c689afa86bcbbf673c165b004d0bf558f35",
    "current_sha256": "688a7ee742d9456d56a55c04a3596c689afa86bcbbf673c165b004d0bf558f35"
  },
  {
    "file": "backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java",
    "identical_to_BASE": true,
    "base_sha256": "e85b3ae680bf1de3406ec5e4895b2922d57b992c2b74d2ae82472055c7727fcb",
    "current_sha256": "e85b3ae680bf1de3406ec5e4895b2922d57b992c2b74d2ae82472055c7727fcb"
  },
  {
    "file": "backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java",
    "identical_to_BASE": true,
    "base_sha256": "8c7fd709e763eba25900937d3147724a97f2f06ed79e08a8affd45217d25f201",
    "current_sha256": "8c7fd709e763eba25900937d3147724a97f2f06ed79e08a8affd45217d25f201"
  },
  {
    "file": "packages/shared/tests/i18n-parity.test.ts",
    "identical_to_BASE": true,
    "base_sha256": "9b805142d02514001e0dba5ec29d8ad1466a1b5fd9448ba47ef7da028a13464b",
    "current_sha256": "9b805142d02514001e0dba5ec29d8ad1466a1b5fd9448ba47ef7da028a13464b"
  },
  {
    "file": "packages/shared/tests/message-placeholder.test.ts",
    "identical_to_BASE": true,
    "base_sha256": "92052b1f76ff971e48baf0a2823fc85c270a5f76a2aefce8c2499b27918c1b32",
    "current_sha256": "92052b1f76ff971e48baf0a2823fc85c270a5f76a2aefce8c2499b27918c1b32"
  }
]
```

## Scope and verification limits

- `git diff --check -- <eight modified products>` passed, no whitespace diagnostics; new test was read in full.
- `git diff --name-only BASE -- 'backend/known-*'` was empty. Existing known-baseline bytes and checker behavior were not relaxed.
- `git diff --name-only --cached` was empty. HEAD remains BASE; no stage/commit.
- Root concurrently changed its brief/main TDD/ledger; these are excluded from implementer product ownership and were not edited by this implementer.
- Executed exactly the four named shared files and backend parity guard; no all-project pass claim. Frontend typecheck is explicitly RED as above. No deployment/runtime end-to-end claim.
- Fresh independent spec and quality review is still required before root's product commit.

## Exact nine-product manifest

```json
[
  {
    "path": "b-app/src/i18n/locale/zh-CN.ts",
    "status": "modified",
    "base_git_blob": "fbe1f353961f9766379f054e2235c959eaf794ba",
    "current_git_blob": "8b9997ef2f718f68d985dc1114d7a3c989feca0a",
    "base_sha256": "b1a2da06beeb7a5dfb9861da41ca6481ce9e63f06e9d0377ff51a46667015e77",
    "current_sha256": "81324a3690ec6e55b6648babf65198e3be41fbf1389e37ab6aabd6df065ee96a"
  },
  {
    "path": "b-app/src/i18n/locale/en.ts",
    "status": "modified",
    "base_git_blob": "4ca936950dfa85a1a8eacdbf6bb46589609d7297",
    "current_git_blob": "e877dbd83028d9645418d7e841c8025ed1d13ce7",
    "base_sha256": "7aa6d8eda03834ff331d3098b7bdfb3da187f28941893b7f523abd91b581d39f",
    "current_sha256": "d73f08cf39c13be202048ca36d0315cb3072f7f19f07847907300a5d4436f9e3"
  },
  {
    "path": "b-app/src/i18n/locale/ar.ts",
    "status": "modified",
    "base_git_blob": "95f89d57e173e91bf8c97407a959f7ca155d9248",
    "current_git_blob": "c2f370b8e757db09b8b8e4f934e443c15784b0eb",
    "base_sha256": "bea9f5a3cc39d125368ab76598d5d96e08b4f04d7f9a41ce500514fdc3111cfc",
    "current_sha256": "212205a6267ebdf0ed16fd6f1e82781c890c2dda00ba6cdae1d216ffacc407d7"
  },
  {
    "path": "backend/shop-app/src/main/resources/i18n/messages.properties",
    "status": "modified",
    "base_git_blob": "12e3cddc2dc91ff0f798a5eb45016763fe98067b",
    "current_git_blob": "6aa414f2d66509a601652560119d37624fcfea15",
    "base_sha256": "4a122ec44fd90c2fad329120b2075de5317ee6fe17c427fd63369e28c610ccce",
    "current_sha256": "5a9d08c8ee8d76ea73d40ab67d30ef4414c31838fd32f395145d8da6c1131eba"
  },
  {
    "path": "backend/shop-app/src/main/resources/i18n/messages_en.properties",
    "status": "modified",
    "base_git_blob": "15d74ac0e8812dcaa70fc5f05069ad6dbe1a1e66",
    "current_git_blob": "42faae218a083a61d069dd905499a3076c6f4bc1",
    "base_sha256": "5ff23a174acb26c0a7111673899b3855c40baa0567c90c63c536d665a5c535ac",
    "current_sha256": "a09b30f8764f4e253c7cdfc93d9a8d5a520e387bdaca97a0ac47b35d8e8cb8fe"
  },
  {
    "path": "backend/shop-app/src/main/resources/i18n/messages_ar.properties",
    "status": "modified",
    "base_git_blob": "118fcef8b605c2bf7c8d51e1211e81ba7cf39387",
    "current_git_blob": "49117530ff52c9b66f4d1ebf44d9e6f47646714a",
    "base_sha256": "d1a4e744b7fa6ee6eac103eca317753238395c121c5f167cfc928ef2bd2265fb",
    "current_sha256": "34090df42a7f2a67f8d9edad2779dc8fa6aadd9cbe722230674dc7e05c2c2cd3"
  },
  {
    "path": "backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java",
    "status": "modified",
    "base_git_blob": "6d71701a2c5b7672a12a5da5827e4eb756d914ad",
    "current_git_blob": "a1209244c530ac885aea379672e34d3521869700",
    "base_sha256": "e9bd93f582f68a22e3899d199206e7ea3df285f36b33ada1bae0354859d39ab7",
    "current_sha256": "d8c3c21a0c820a9fbf8c0ee29c8a249fce90ddc5c75d675f6085403be98968cf"
  },
  {
    "path": "packages/shared/tests/biz-perm-label.test.ts",
    "status": "modified",
    "base_git_blob": "72eda2607e53311863559b402fa722379ce77b61",
    "current_git_blob": "249489c72eb0e860406c0b75be9256eb9182953a",
    "base_sha256": "560df8ba99a644f2bba750aa420e13c0a17c6eeba2039794a2dfc810e4b87786",
    "current_sha256": "df53e450e6ac22ceb395ddfa3c72a33e9f8a5f8baf468f192d91ca477fa2c8d5"
  },
  {
    "path": "packages/shared/tests/stock-shared-source-label.test.ts",
    "status": "new",
    "base_git_blob": null,
    "current_git_blob": "1a385a993d8a0d171dd6ecafd145d2cab6d69492",
    "base_sha256": null,
    "current_sha256": "956c1005e3f22650ed956f4d72b7d6c0a1b6c1b8215f258ecfa713231ec00861"
  }
]
```

## Evidence log pointers and hashes

These `/tmp` logs retain full command output; the substantive result, source attribution, XML metadata, mutation restoration and complete product diff are embedded in this report so the review does not depend solely on scratch logs.

```json
[
  {
    "path": "/tmp/task-60a-a8-red.log",
    "sha256": "6298578f8c74b03fc28843add8f98c8081295ec929fccb3e960c8aad5360efe2"
  },
  {
    "path": "/tmp/task-60a-a8-green.log",
    "sha256": "366a3f326a55e2698668e71a04cac26e3550ff1db8097919f222b626ae450c47"
  },
  {
    "path": "/tmp/task-60a-a8-mutation-placeholder.log",
    "sha256": "8fea4b5a18972660472db1616122b3f3e67ede242f630ce9a7a67c01098ed7da"
  },
  {
    "path": "/tmp/task-60a-a8-mutation-argument-order.log",
    "sha256": "621149e446968e5b67a25315b80b8f5043c636ea47e5c4a78e9d30c3ebcd3b3a"
  },
  {
    "path": "/tmp/task-60a-a8-final-green.log",
    "sha256": "a90a8d1d777a94f7bdf4d75a97535c9704b335689d13a75bcf0c651b756826dd"
  },
  {
    "path": "/tmp/task-60a-a8-backend.log",
    "sha256": "b039fb3fa1af3fad74b39214eb6bbf5b8d777a92ee2532d2b8a4abdddb7ffb01"
  },
  {
    "path": "/tmp/task-60a-a8-typecheck.log",
    "sha256": "2933113c100f17dd75d418d2c3c6968f1a9c4e4eaf11c9a3897696ce8ed1bde5"
  },
  {
    "path": "/tmp/task-60a-a8-typecheck-final.log",
    "sha256": "2933113c100f17dd75d418d2c3c6968f1a9c4e4eaf11c9a3897696ce8ed1bde5"
  }
]
```

## Full nine-file BASE-to-current product diff

Tracked files: `git diff --binary 1d1610278120b97dbfc0b5eb01e0466f141b6735 -- <eight modified products>`; new test: `git diff --no-index -- /dev/null packages/shared/tests/stock-shared-source-label.test.ts` (exit 1 is expected for a non-empty new-file diff). Exactly nine `diff --git` sections follow; root docs/ledger are excluded.

```diff
diff --git a/b-app/src/i18n/locale/ar.ts b/b-app/src/i18n/locale/ar.ts
index 95f89d57e..c2f370b8e 100644
--- a/b-app/src/i18n/locale/ar.ts
+++ b/b-app/src/i18n/locale/ar.ts
@@ -42,6 +42,7 @@ export default {
     at: "{t}",
   },
   common: {
+    delete: "حذف",
     loadFailed: "تعذّر التحميل",
     loadFailedTip: "الشبكة على الأرجح. تحقّق منها ثم أعد المحاولة.",
     remove: "إزالة", search: "بحث", yes: "نعم", no: "لا", back: "رجوع", confirm: "تأكيد", cancel: "إلغاء", done: "تم", save: "حفظ", loading: "جارٍ التحميل…", empty: "لا يوجد محتوى بعد", retry: "إعادة", saved: "تم الحفظ", all: "الكل",
@@ -2514,6 +2515,8 @@ entryHint: "من يشتري · من توقف", total: "عدد العملاء", r
     statusNONE: "غير مسجّل", statusAPPLYING: "قيد المراجعة", statusREJECTED: "مرفوض", statusACTIVE: "نشط", statusSUSPENDED: "موقوف",
   },
   perm: {
+    "biz:booking:view": "عرض المواعيد",
+    "biz:booking:manage": "إنشاء المواعيد وتعديلها وإلغاؤها",
     "biz:receive": "استلام البضائع والفرز",
     "biz:verify": "تسليم الطلبات بالرمز",
     "biz:ship": "الشحن وتأكيد التسليم",
diff --git a/b-app/src/i18n/locale/en.ts b/b-app/src/i18n/locale/en.ts
index 4ca936950..e877dbd83 100644
--- a/b-app/src/i18n/locale/en.ts
+++ b/b-app/src/i18n/locale/en.ts
@@ -42,6 +42,7 @@ export default {
     at: "{t}",
   },
   common: {
+    delete: "Delete",
     loadFailed: "Could not load",
     loadFailedTip: "Most likely the network. Check it and try again.",
     remove: "Remove", search: "Search", yes: "Yes", no: "No", back: "Back", confirm: "OK", cancel: "Cancel", done: "Done", save: "Save", loading: "Loading…", empty: "Nothing here yet", retry: "Retry", saved: "Saved", all: "All",
@@ -2516,6 +2517,8 @@ entryHint: "Who buys · who lapsed", total: "Customers", repeatRate: "Repeat rat
     statusNONE: "Not registered", statusAPPLYING: "Under review", statusREJECTED: "Rejected", statusACTIVE: "Active", statusSUSPENDED: "Suspended",
   },
   perm: {
+    "biz:booking:view": "View appointments",
+    "biz:booking:manage": "Create, change and cancel appointments",
     "biz:receive": "Goods-in and picking",
     "biz:verify": "Redeem pickups",
     "biz:ship": "Ship and mark delivered",
diff --git a/b-app/src/i18n/locale/zh-CN.ts b/b-app/src/i18n/locale/zh-CN.ts
index fbe1f3539..8b9997ef2 100644
--- a/b-app/src/i18n/locale/zh-CN.ts
+++ b/b-app/src/i18n/locale/zh-CN.ts
@@ -338,7 +338,7 @@ export default {
       valueMerged: "平台已存在「{name}」，已直接引用",
       // 三件事在一屏：用不用、什么顺序、叫什么
       adjustHint: "✕ 移除档位，＋ 从平台选用或新建。重命名仅影响本店显示，不影响跨店比价。",
-      save: "保存", cancel: "取消", reset: "恢复平台默认", saved: "已保存",
+      save: "保存", delete: "删除", cancel: "取消", reset: "恢复平台默认", saved: "已保存",
       addableHere: "可加回",
       catAllRemoved: "已全部移除",
       catAllRemovedProps: "已全部移除",
@@ -2825,6 +2825,8 @@ entryHint: "谁在买 · 谁不来了",
   // 后端也带了一份中文（BizPerms.LABELS），这里这份多了英文/阿语；
   // 两份对不上时以后端为准（它是判权那份），但界面优先用这里的本地化。
   perm: {
+    "biz:booking:view": "查看预约",
+    "biz:booking:manage": "创建、调整及取消预约",
     "biz:receive": "到货登记与分拣",
     "biz:verify": "核销取货",
     "biz:ship": "发货与标记送达",
diff --git a/backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java b/backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java
index 6d71701a2..a1209244c 100644
--- a/backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java
+++ b/backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java
@@ -1,5 +1,7 @@
 package ai.neargo.shop.invbridge;
 
+import ai.neargo.shop.common.BizException;
+import ai.neargo.shop.common.ErrorCode;
 import ai.neargo.shop.inventory.config.ConditionalOnInventory;
 import ai.neargo.shop.inventory.service.InventoryAclService;
 import ai.neargo.shop.product.entity.PrdGoods;
@@ -153,7 +155,7 @@ public class StockReconcileService {
         if (source == null) return;
         for (String other : merchants.storeNos(entityNo)) {
             if (!Objects.equals(storeNo, other) && Objects.equals(source, acl.stockLocationOf(entityNo, other))) {
-                throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.STOCK_SHARED_SOURCE,
+                throw BizException.of(ErrorCode.STOCK_SHARED_SOURCE,
                         storeNo, other);
             }
         }
diff --git a/backend/shop-app/src/main/resources/i18n/messages.properties b/backend/shop-app/src/main/resources/i18n/messages.properties
index 12e3cddc2..6aa414f2d 100644
--- a/backend/shop-app/src/main/resources/i18n/messages.properties
+++ b/backend/shop-app/src/main/resources/i18n/messages.properties
@@ -260,7 +260,7 @@ err.points.operation_rejected=积分操作未受理（{0}），请核对当前
 points.statement.notice.title=门店积分账单待确认
 points.statement.notice.body=门店 {0} 的积分账单 {1}（版本 {2}）已出单，请核对；本通知送达后享有完整 {3} 天异议期，期满无异议将自动确认。
 
-err.inventory.shared_source=该门店与门店 {1} 共用发货库存，暂不支持分别同步全部库存，请先配置独立发货库位
+err.inventory.shared_source=门店 {0} 与门店 {1} 共用发货库存，暂不支持分别同步全部库存，请先配置独立发货库位
 
 # Entitlement/member messages merged from stored-value feature
 err.entitlement.feature_unavailable=此权益功能暂未开放，请稍后再试
diff --git a/backend/shop-app/src/main/resources/i18n/messages_ar.properties b/backend/shop-app/src/main/resources/i18n/messages_ar.properties
index 118fcef8b..49117530f 100644
--- a/backend/shop-app/src/main/resources/i18n/messages_ar.properties
+++ b/backend/shop-app/src/main/resources/i18n/messages_ar.properties
@@ -268,7 +268,7 @@ err.points.operation_rejected=لم تُقبل عملية النقاط ({0}). ت
 points.statement.notice.title=كشف نقاط المتجر بانتظار التأكيد
 points.statement.notice.body=كشف النقاط {1} (الإصدار {2}) للمتجر {0} متاح. لديك {3} أيام كاملة بعد الإشعار للاعتراض، وبعدها سيتم تأكيده تلقائياً.
 
-err.inventory.shared_source=يشترك هذا المتجر في مصدر المخزون مع المتجر {1}. يرجى إعداد مواقع مخزون مستقلة قبل تفعيل المزامنة.
+err.inventory.shared_source=يشترك المتجر {0} في مصدر المخزون مع المتجر {1}. يرجى إعداد مواقع مخزون مستقلة قبل تفعيل المزامنة.
 
 # Entitlement/member messages merged from stored-value feature
 err.entitlement.feature_unavailable=ميزة المزايا هذه غير متاحة حاليًا. يرجى المحاولة لاحقًا.
diff --git a/backend/shop-app/src/main/resources/i18n/messages_en.properties b/backend/shop-app/src/main/resources/i18n/messages_en.properties
index 15d74ac0e..42faae218 100644
--- a/backend/shop-app/src/main/resources/i18n/messages_en.properties
+++ b/backend/shop-app/src/main/resources/i18n/messages_en.properties
@@ -259,7 +259,7 @@ err.points.operation_rejected=Points operation rejected ({0}). Check the current
 points.statement.notice.title=Store points statement awaiting confirmation
 points.statement.notice.body=Points statement {1} (revision {2}) for store {0} is available. You have {3} full days after notification to raise a dispute; it will then be confirmed automatically.
 
-err.inventory.shared_source=This store shares its stock source with store {1}. Configure independent stock locations before enabling stock sync.
+err.inventory.shared_source=Store {0} shares its stock source with store {1}. Configure independent stock locations before enabling stock sync.
 
 # Entitlement/member messages merged from stored-value feature
 err.entitlement.feature_unavailable=This benefits feature is not available yet. Please try again later.
diff --git a/packages/shared/tests/biz-perm-label.test.ts b/packages/shared/tests/biz-perm-label.test.ts
index 72eda2607..249489c72 100644
--- a/packages/shared/tests/biz-perm-label.test.ts
+++ b/packages/shared/tests/biz-perm-label.test.ts
@@ -55,12 +55,18 @@ function javaLabels(): Record<string, string> {
 /** 某个语言包里 `perm: { … }` 段：权限码 → 文案 */
 function localeLabels(locale: string): Record<string, string> {
   const src = readFileSync(join(ROOT, `b-app/src/i18n/locale/${locale}.ts`), "utf8");
+  return parseLocaleLabels(src, locale);
+}
+
+function parseLocaleLabels(src: string, locale: string): Record<string, string> {
   const at = src.indexOf("  perm: {");
   expect(at, `${locale}.ts 里找不到 perm 段`).toBeGreaterThan(0);
   const block = src.slice(at, src.indexOf("},", at));
   const out: Record<string, string> = {};
   for (const m of block.matchAll(/"(biz:[a-z:]+)":\s*"([^"]+)"/g)) {
-    out[m[1]!] = m[2]!;
+    const code = m[1]!;
+    if (Object.hasOwn(out, code)) throw new Error(`${locale}.ts perm 重复权限码：${code}`);
+    out[code] = m[2]!;
   }
   return out;
 }
@@ -69,6 +75,22 @@ describe("权限码的人话文案", () => {
   const java = javaLabels();
   const consts = codeConstants();
 
+  it.each([
+    ["zh-CN", "查看预约", "创建、调整及取消预约"],
+    ["en", "View appointments", "Create, change and cancel appointments"],
+    ["ar", "عرض المواعيد", "إنشاء المواعيد وتعديلها وإلغاؤها"],
+  ])("%s 的预约文案区分查看与创建、调整及取消", (locale, view, manage) => {
+    const labels = localeLabels(locale);
+    expect(labels["biz:booking:view"]).toBe(view);
+    expect(labels["biz:booking:manage"]).toBe(manage);
+  });
+
+  it.each(LOCALES)("%s 的实际 perm 解析器拒绝重复权限码", (locale) => {
+    const src = readFileSync(join(ROOT, `b-app/src/i18n/locale/${locale}.ts`), "utf8");
+    const duplicate = src.replace('  perm: {', '  perm: {\n    "biz:receive": "duplicate",');
+    expect(() => parseLocaleLabels(duplicate, locale)).toThrow(`perm 重复权限码：biz:receive`);
+  });
+
   it("两个源都解析得到（正则失效时不要静默通过）", () => {
     expect(Object.keys(java).length, "BizPerms.LABELS 解析为空").toBeGreaterThanOrEqual(13);
     expect(Object.keys(localeLabels("zh-CN")).length, "zh-CN 的 perm 段解析为空")
diff --git a/packages/shared/tests/stock-shared-source-label.test.ts b/packages/shared/tests/stock-shared-source-label.test.ts
new file mode 100644
index 000000000..1a385a993
--- /dev/null
+++ b/packages/shared/tests/stock-shared-source-label.test.ts
@@ -0,0 +1,49 @@
+import { readFileSync } from "node:fs";
+import { join } from "node:path";
+import { describe, expect, it } from "vitest";
+
+const ROOT = join(import.meta.dirname, "../../..");
+const KEY = "err.inventory.shared_source";
+const LOCALES = ["messages.properties", "messages_en.properties", "messages_ar.properties"] as const;
+const PRODUCERS = ["StockReconcileService", "InventoryWritebackService"] as const;
+
+function sharedSourceMessage(file: string): string {
+  const src = readFileSync(join(ROOT, "backend/shop-app/src/main/resources/i18n", file), "utf8");
+  const entries = src.split(/\r?\n/).filter((line) => line.startsWith(`${KEY}=`));
+  expect(entries, `${file}: shared-source key must occur exactly once`).toHaveLength(1);
+  return entries[0]!.slice(KEY.length + 1);
+}
+
+function sharedSourceArguments(producer: string): string[] {
+  const src = readFileSync(join(ROOT,
+    `backend/shop-app/src/main/java/ai/neargo/shop/invbridge/${producer}.java`), "utf8");
+  // Accept the existing qualified call and normal imports; read both real producers.
+  const calls = [...src.matchAll(/BizException\.of\(\s*(?:[\w.]*ErrorCode\.)STOCK_SHARED_SOURCE\s*,([^)]*)\)/g)];
+  expect(calls, `${producer}: expected one shared-source exception producer`).toHaveLength(1);
+  return calls[0]![1]!.split(",").map((arg) => arg.trim());
+}
+
+describe("shared stock source message contract", () => {
+  it.each(PRODUCERS)("%s supplies current store then conflicting store", (producer) => {
+    expect(sharedSourceArguments(producer)).toEqual(["storeNo", "other"]);
+  });
+
+  it.each(LOCALES)("%s displays both supplied store slots", (file) => {
+    const message = sharedSourceMessage(file);
+    const indices = [...message.matchAll(/\{(\d+)(?:,[^{}]*)?\}/g)].map((match) => Number(match[1]));
+    expect([...new Set(indices)].sort(), `${file}: current {0} and conflicting {1} stores are both required`)
+      .toEqual([0, 1]);
+
+    const sampleStores: Record<string, string> = { storeNo: "CURRENT-STORE-17", other: "CONFLICTING-STORE-92" };
+    for (const producer of PRODUCERS) {
+      const args = sharedSourceArguments(producer).map((name) => sampleStores[name]);
+      expect(args).toEqual(["CURRENT-STORE-17", "CONFLICTING-STORE-92"]);
+      // Literal slot projection checks consumer-visible data coverage only.
+      // This is not a MessageFormat implementation or a production Java runtime test.
+      const display = message.replace(/\{([01])\}/g, (_, index: string) => args[Number(index)]!);
+      expect(display).toContain("CURRENT-STORE-17");
+      expect(display).toContain("CONFLICTING-STORE-92");
+      expect(display).not.toMatch(/\{\d+[^{}]*\}/);
+    }
+  });
+});
```
