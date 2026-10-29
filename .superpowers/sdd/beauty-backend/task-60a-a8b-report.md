# Task60A-A8b implementation report

Status: IMPLEMENTED, products FROZEN for independent specification and quality review. Full B vue-tsc has actually reached zero errors; the inherited A8 type defect has verification evidence for closure by root. No commit, stage, push, merge, deployment, dependency installation or subagent.

## Identity and approved scope

Worktree `/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`; branch `codex/beauty-foundation`; implementation BASE and current HEAD `4ed3745dce4b0959486f3dd141333dce6adc0a3f`. Tier 1, main TDD §2.33 released before implementation. Approved brief: `.superpowers/sdd/beauty-backend/task-60a-a8b-brief.md`. Read CLAUDE.md, ai-shop-dev-standards, actual Java controller/request/response, shared CustomerReference and reexport, existing endpoint/mock and order-phase1 wire test before coding.

Exactly four products:

1. `b-app/src/api/contract.ts`: imports existing shared CustomerReference and request, documents scoped short-lived reference and absence of payment consent, adds typed MerchantApi method.
2. `b-app/src/api/requests.ts`: exports ResolveMemberCodeReq with exactly `memberCode: string` and `storeNo: string`.
3. `b-app/src/api/http.ts`: real httpApi method uses existing E.mResolveMemberCode.path and http.post<CustomerReference>, projects precisely memberCode/storeNo with satisfies ResolveMemberCodeReq.
4. `b-app/tests/member-code-api.test.ts`: three adapter wire unit tests import REAL httpApi, replace only network transport, assert literal existing POST route/body despite extra structural input identity fields, retain opaque response fields/ISO UTC expiry, and propagate exact transport error without a fabricated reference.

No copied response DTO, any/index-signature suppression, new route/client/page, credentials in URL/query, logging/persistence, or Java/shared-type/config/lockfile change. Existing marketing mock still throws and cannot mint an identity. Shared endpoint/owner sources and all nine accepted A8 products are byte-identical to BASE, recorded in the manifest. Root's concurrent progress.md change is unrelated and untouched.

## Requirements and evidence

| AC | Result | Evidence |
|---|---|---|
| A8b-1 | Shared response reused; exact two-field request and interface declaration | complete four-product diff + full vue-tsc exit 0 |
| A8b-2 | Real adapter route/body, identity-field exclusion, unchanged response/error semantics | all three member-code-api tests |
| A8b-3 | Actual missing-method RED, GREEN, wire mutation RED, exact restore/final GREEN, full B type gate and affected shared guard | logs below |

Commands use existing root binaries; no install. Every Vitest command is finite `run`, with the already present config.

| Log | cwd and exact command/action | Actual result |
|---|---|---|
| 01-red.log | b-app: `../node_modules/.bin/vitest run --config vitest.config.mts tests/member-code-api.test.ts` before production edits | exit 1; 3 failed with real `httpApi.mResolveMemberCode is not a function`, not a type/import failure |
| 02-green.log | same focused command after minimal production edits | exit 0; 3 passed |
| 03-b-vue-tsc.log | b-app: `../node_modules/.bin/vue-tsc --noEmit` | exit 0; no diagnostics (entire B project) |
| 04-shared-field-guard.log | packages/shared: `../../node_modules/.bin/vitest run --config vitest.config.mts tests/biz-contract-fields.test.ts` | exit 1; initial command typo, config is actually .ts; startup failure, not behavioral RED |
| 04b-shared-field-guard.log | packages/shared: `../../node_modules/.bin/vitest run --config vitest.config.ts tests/biz-contract-fields.test.ts` | exit 0; 1 file/8 tests passed |
| 05-b-suite.log | b-app: `../node_modules/.bin/vitest run --config vitest.config.mts` | exit 0; 43 files/242 tests passed |
| 06-mutation-red.log | swap only payload.memberCode/payload.storeNo values in real httpApi, run focused command | exit 1; 1 body assertion failed, 2 passed; received values visibly swapped |
| 07-restore.log | finally restore http.ts original bytes and compare SHA-256 | exact bytes restored; SHA-256 before/restored `8b89041d7272ab22648e79f5e3057a8ce26f540084efc6317ea53f799d3f2209` |
| 08-final-green.log | same focused command after exact restore | exit 0; 3 passed |
| 09-diff-check.log | `git diff --check -- b-app/src/api/contract.ts b-app/src/api/requests.ts b-app/src/api/http.ts b-app/tests/member-code-api.test.ts` | exit 0 |

The extra full B test run was triggered by the applied test-driven-development skill's explicit project-suite requirement; it covered existing B tests once. No repeated passed suite, broad shared suite, backend/native suite, or generated Java XML was added. The shared guard scans actual frontend contract declarations/endpoints/shared interfaces against actual Java controller response imports/record fields; this includes the newly declared CustomerReference method. It retains its existing optional-field and C-app baseline rules unchanged.

## Coverage boundaries and concerns

These are HTTP adapter wire unit tests, with only transport mocked. They do not claim live HTTP authorization, member-code issuance/expiry runtime behavior, a new frontend page, customer payment consent, or business-provider integration coverage. Existing Java tenant/entity/actor authority, store permissions, reference scoping and expiry behavior are untouched. No additional baseline B type failures remain: actual full vue-tsc exit is zero. The one failed config-path invocation is preserved transparently and corrected without changing config.

Independent specification/quality review and any later local commit remain root-owned. Root docs/ledger were not edited by this implementer. Products are frozen; concerns: none within approved implementation scope.

## Frozen manifest

```json
{
  "status": "FROZEN_FOR_INDEPENDENT_REVIEW",
  "worktree": "/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex",
  "branch": "codex/beauty-foundation",
  "implementationBase": "4ed3745dce4b0959486f3dd141333dce6adc0a3f",
  "head": "4ed3745dce4b0959486f3dd141333dce6adc0a3f",
  "products": [
    {
      "path": "b-app/src/api/contract.ts",
      "sha256": "a325399a923e1a74b3f40c982c758290880c8370af2bb8e46dab0f218eeab2b6",
      "gitBlob": "a4336da7827143b1a8c5c410ddf7e9a3e836c10e",
      "bytes": 109095,
      "baselineSha256": "fd9b092128f04e4cb30d3655aeaf0200e95ef555d4ef47673c4c36d6a1147ad9"
    },
    {
      "path": "b-app/src/api/requests.ts",
      "sha256": "12bc1e04ad3e6a2b76f976db0ca97dde666e551b1dd61aefd77266aea3704810",
      "gitBlob": "69889a8e6865c79db5374812a1528c1b763b450d",
      "bytes": 32732,
      "baselineSha256": "eaecdec8163cb3c0579c1588fa9ed869944a13bc5c499d998c357ff24674cf01"
    },
    {
      "path": "b-app/src/api/http.ts",
      "sha256": "8b89041d7272ab22648e79f5e3057a8ce26f540084efc6317ea53f799d3f2209",
      "gitBlob": "22f349f4fab508cbea165f5a9d9ea56b05af346b",
      "bytes": 46014,
      "baselineSha256": "e8311584b7fc186fca1b6aefa0d5def1ce1cd36f5e4b48afc347e5b18e350cec"
    },
    {
      "path": "b-app/tests/member-code-api.test.ts",
      "sha256": "3236f37b6ace489dddec2041a28f775d508cf3b8b201ad1411fdbd19f4309da1",
      "gitBlob": "a1fde7ce9ae46adea40d3a46969bde48aea6b09d",
      "bytes": 1994,
      "baselineSha256": null
    }
  ],
  "readOnlyUnchanged": [
    {
      "path": "b-app/src/api/endpoints.ts",
      "sha256": "2c63ca6b89049ad80c3b7ae142a1b3d5c277c170ef02c174fe1d67c0af9926e2",
      "byteIdenticalToBase": true
    },
    {
      "path": "b-app/src/api/mocks/marketing.ts",
      "sha256": "79b4f91632d843b570a0e89c158c43bfc5bd722d6beb5e2f8bda3be8da2cdb5b",
      "byteIdenticalToBase": true
    },
    {
      "path": "packages/shared/src/types/member.ts",
      "sha256": "60afdc15562cee7452c281344eccddc51dba5d471f3170e74ffe7f8e1d903971",
      "byteIdenticalToBase": true
    },
    {
      "path": "packages/shared/src/types/index.ts",
      "sha256": "ef514c12e480506a36bf084c9e470ab41fd7facabce61e087a8866564096fb3d",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizMemberCodeController.java",
      "sha256": "d81ec03a22ffbd08704e55ff335b8444732e103e97589b30f39b2c737124d73b",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-core/src/main/java/ai/neargo/shop/member/dto/MemberResolveCommand.java",
      "sha256": "02ef415b92758cf034f2dbc27bf865c92934f3c1902ca878245c4d13c50f2aa0",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-core/src/main/java/ai/neargo/shop/member/dto/MemberCodeViews.java",
      "sha256": "923a33278fa4a0df3a06afbf7e5b591848461bbc926426cd7f08af36a7d26ad7",
      "byteIdenticalToBase": true
    },
    {
      "path": "packages/shared/tests/biz-contract-fields.test.ts",
      "sha256": "b425c752ece559f2776b9b5dedcbd9f8fb38e9f1ec1f9c3200f7bd36ffaf8976",
      "byteIdenticalToBase": true
    },
    {
      "path": "b-app/src/i18n/locale/ar.ts",
      "sha256": "212205a6267ebdf0ed16fd6f1e82781c890c2dda00ba6cdae1d216ffacc407d7",
      "byteIdenticalToBase": true
    },
    {
      "path": "b-app/src/i18n/locale/en.ts",
      "sha256": "d73f08cf39c13be202048ca36d0315cb3072f7f19f07847907300a5d4436f9e3",
      "byteIdenticalToBase": true
    },
    {
      "path": "b-app/src/i18n/locale/zh-CN.ts",
      "sha256": "81324a3690ec6e55b6648babf65198e3be41fbf1389e37ab6aabd6df065ee96a",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-app/src/main/java/ai/neargo/shop/invbridge/StockReconcileService.java",
      "sha256": "d8c3c21a0c820a9fbf8c0ee29c8a249fce90ddc5c75d675f6085403be98968cf",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-app/src/main/resources/i18n/messages.properties",
      "sha256": "5a9d08c8ee8d76ea73d40ab67d30ef4414c31838fd32f395145d8da6c1131eba",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-app/src/main/resources/i18n/messages_ar.properties",
      "sha256": "34090df42a7f2a67f8d9edad2779dc8fa6aadd9cbe722230674dc7e05c2c2cd3",
      "byteIdenticalToBase": true
    },
    {
      "path": "backend/shop-app/src/main/resources/i18n/messages_en.properties",
      "sha256": "a09b30f8764f4e253c7cdfc93d9a8d5a520e387bdaca97a0ac47b35d8e8cb8fe",
      "byteIdenticalToBase": true
    },
    {
      "path": "packages/shared/tests/biz-perm-label.test.ts",
      "sha256": "df53e450e6ac22ceb395ddfa3c72a33e9f8a5f8baf468f192d91ca477fa2c8d5",
      "byteIdenticalToBase": true
    },
    {
      "path": "packages/shared/tests/stock-shared-source-label.test.ts",
      "sha256": "956c1005e3f22650ed956f4d72b7d6c0a1b6c1b8215f258ecfa713231ec00861",
      "byteIdenticalToBase": true
    }
  ]
}
```

## Complete BASE-to-product diff (all four files)

```diff
diff --git a/b-app/src/api/contract.ts b/b-app/src/api/contract.ts
index 3a5c00869..a4336da78 100644
--- a/b-app/src/api/contract.ts
+++ b/b-app/src/api/contract.ts
@@ -44,6 +44,7 @@ import type {
   CrossStoreCompare,
   CrossStoreOverview,
   CurrencyCode,
+  CustomerReference,
   DailyFlowPage,
   DailyReport,
   DeliveryRule,
@@ -813,6 +814,7 @@ import type {
   ZipPlanReq,
   ZipImported,
   PickupSelfBuildReq,
+  ResolveMemberCodeReq,
   StaffLoginReq,
   StoreEditReq,
   StoreFulfillmentSaveReq,
@@ -1757,6 +1759,8 @@ export interface MerchantApi {
 
   // ---- 客户与复购（B-11.2.8）
   mCustomers(): Promise<MerchantCustomer[]>;
+  /** 识别会员码，返回仅在原主体、门店和操作人范围内有效的短期顾客引用；不代表支付授权。 */
+  mResolveMemberCode(payload: ResolveMemberCodeReq): Promise<CustomerReference>;
 
   /**
    * 会员列表。
diff --git a/b-app/src/api/http.ts b/b-app/src/api/http.ts
index 9990d3465..22f349f4f 100644
--- a/b-app/src/api/http.ts
+++ b/b-app/src/api/http.ts
@@ -41,6 +41,7 @@ import type {
   RecognizeGoodsReq,
   ReplyReviewReq,
   ReportShortageReq,
+  ResolveMemberCodeReq,
   SaveGoodsReqBody,
   SaveShipSettingReq,
   SaveSpecTemplateReq,
@@ -82,6 +83,7 @@ import type {
   CouponRedeemView,
   CrossStoreCompare,
   CrossStoreOverview,
+  CustomerReference,
   DailyFlowPage,
   DailyReport,
   DeliveryRule,
@@ -623,6 +625,11 @@ export const httpApi: MerchantApi = {
     } satisfies ToggleCampaignReq),
 
   mCustomers: () => http.get<MerchantCustomer[]>(E.mCustomers.path),
+  mResolveMemberCode: (payload) =>
+    http.post<CustomerReference>(E.mResolveMemberCode.path, {
+      memberCode: payload.memberCode,
+      storeNo: payload.storeNo,
+    } satisfies ResolveMemberCodeReq),
   mMembers: (q) => http.get<PageResult<Member>>(E.mMembers.path, { ...(q ?? {}) }),
   mMemberStats: (storeNo) => http.get<MemberStats>(E.mMemberStats.path, { storeNo }),
   mMemberDetail: (memberNo) =>
diff --git a/b-app/src/api/requests.ts b/b-app/src/api/requests.ts
index 3b0b20b59..69889a8e6 100644
--- a/b-app/src/api/requests.ts
+++ b/b-app/src/api/requests.ts
@@ -110,6 +110,14 @@ export interface ShareKitQuery {
   goodsNo?: string;
 }
 
+// ---------------------------------------------------------------- 会员凭证
+
+/** 仅提交扫描凭证与选定门店；租户、主体及操作人身份由后端会话确定。 */
+export interface ResolveMemberCodeReq {
+  memberCode: string;
+  storeNo: string;
+}
+
 // ---------------------------------------------------------------- 商品
 
 export interface GoodsListQuery {
diff --git a/b-app/tests/member-code-api.test.ts b/b-app/tests/member-code-api.test.ts
new file mode 100644
index 000000000..a1fde7ce9
--- /dev/null
+++ b/b-app/tests/member-code-api.test.ts
@@ -0,0 +1,62 @@
+import { beforeEach, describe, expect, it, vi } from "vitest";
+
+const { post } = vi.hoisted(() => ({ post: vi.fn() }));
+vi.mock("@shared/net/http-client", () => ({ http: { post }, clientTag: () => "test" }));
+import { httpApi } from "@/api/http";
+
+beforeEach(() => { post.mockReset(); });
+
+describe("统一会员码 HTTP 适配合同", () => {
+  it("仅向既有识别路径 POST 扫描凭证与门店，不转发调用方身份字段", async () => {
+    post.mockResolvedValue({
+      customerRef: "synthetic-opaque-reference",
+      maskedName: "测*",
+      expiresAt: "2026-10-25T08:05:00Z",
+    });
+    const input = {
+      memberCode: "synthetic-member-code",
+      storeNo: "synthetic-store",
+      tenantNo: "synthetic-tenant",
+      entityNo: "synthetic-entity",
+      actorNo: "synthetic-actor",
+      userNo: "synthetic-user",
+    };
+
+    await httpApi.mResolveMemberCode(input);
+
+    expect(post).toHaveBeenCalledExactlyOnceWith("/biz/member-code/resolve", {
+      memberCode: "synthetic-member-code",
+      storeNo: "synthetic-store",
+    });
+  });
+
+  it("保留后端返回的不透明顾客引用、脱敏名称和 ISO UTC 失效时间", async () => {
+    post.mockResolvedValue({
+      customerRef: "synthetic-opaque-reference",
+      maskedName: "测*",
+      expiresAt: "2026-10-25T08:05:00Z",
+    });
+
+    const result = await httpApi.mResolveMemberCode({
+      memberCode: "synthetic-member-code",
+      storeNo: "synthetic-store",
+    });
+
+    expect(result).toEqual({
+      customerRef: "synthetic-opaque-reference",
+      maskedName: "测*",
+      expiresAt: "2026-10-25T08:05:00Z",
+    });
+  });
+
+  it("透传传输异常，不伪造顾客引用", async () => {
+    const failure = new Error("synthetic transport failure");
+    post.mockRejectedValue(failure);
+
+    await expect(httpApi.mResolveMemberCode({
+      memberCode: "synthetic-member-code",
+      storeNo: "synthetic-store",
+    })).rejects.toBe(failure);
+    expect(post).toHaveBeenCalledTimes(1);
+  });
+});

```
