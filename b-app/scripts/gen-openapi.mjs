// B 端契约生成器 —— 由 endpoints.ts + api/requests.ts + packages/shared/types
// 生成 docs/api/openapi-b.yaml。
//
// **本轮补齐了 requestBody 与响应 schema**（此前只有路径/方法/鉴权/摘要）。
// 之所以此前不生成：B 端没有独立的 wire 入参类型，从方法签名（`mShip(orderNo, expressNo)`
// 这种位置参数）反推 JSON body 只能靠猜，猜出来的契约比没有契约更坏。
// 现在 `src/api/requests.ts` 建好了，入参有了唯一真源，且 http.ts 用 `satisfies` 焊死 ——
// 后端实现那 35 条端点时终于有入参定义可依，而不是去读前端源码。
//
// 与 C 端生成器同构（同一套 collect / dataSchema / toYaml），差别只有：
//   前缀 /biz、契约文件、以及下面两张手写映射表。
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createGenerator } from "ts-json-schema-generator";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const outFile = path.resolve(root, "../docs/api/openapi-b.yaml");
const typesFile = path.resolve(root, "../packages/shared/src/types/index.ts");
const reqFile = path.resolve(root, "src/api/requests.ts");

// ---------------------------------------------------------------- 1. 端点表
const epSrc = fs.readFileSync(path.resolve(root, "src/api/endpoints.ts"), "utf8");
const body = epSrc.slice(epSrc.indexOf("export const ENDPOINTS"));

const endpoints = {};
const re =
  /(\w+):\s*\{\s*method:\s*"(GET|POST|PUT)",\s*path:\s*"([^"]+)",\s*auth:\s*(true|false),\s*summary:\s*"([^"]*)"/g;
let m;
while ((m = re.exec(body))) {
  endpoints[m[1]] = { method: m[2], path: m[3], auth: m[4] === "true", summary: m[5] };
}
if (!Object.keys(endpoints).length) {
  throw new Error("没有从 endpoints.ts 解析到任何端点 —— 表结构变了？");
}

/*
 * 已知用了「复数资源名 + id」的端点。**清单只准变短。**
 *
 * 原来这里是**硬失败**，于是 18 条存量端点（整个库存域 + 门店履约）
 * 让这个生成器**一次都跑不起来** —— `openapi-b.yaml` 从那天起就停在原地，
 * 而它不在 `check-generated-docs` 的名单里，没有任何东西会报。
 * 一条规矩把自己的产物锁死，还没人知道，这比规矩被破坏更糟。
 *
 * 2026-08-28 数下来：c-app 3 条（都在 goods/address 豁免里）、b-app 18 条、
 * **ops-web 98 条**（它的生成器压根没这条规矩）。126 个线上活着的端点是复数，
 * 而规矩只活在两个生成器里。要不要把 ADR-007 改成承认复数，是另一件事 ——
 * 那要动 126 条线上路径或改约定本身，不该由一个文档生成器顺手决定。
 */
const knownPlural = new Set(
  fs
    .readFileSync(path.resolve(here, "known-plural-paths.txt"), "utf8")
    .split("\n")
    .filter((l) => l.trim() && !l.startsWith("#"))
    .map((l) => l.split("\t")[0]),
);
const fixedPlural = [...knownPlural];

// 与 C 端同一条规矩：带 id 的资源段用单数；前缀必须是 /biz（ADR-007）
for (const [key, ep] of Object.entries(endpoints)) {
  const bad = ep.path.match(/\/(\w+s)\/:/);
  if (bad && !/^(goods|address)$/.test(bad[1])) {
    if (!knownPlural.has(key)) {
      throw new Error(
        `端点 "${key}" 的路径 ${ep.path} 用了复数资源名 "${bad[1]}" 且紧跟 id。\n` +
          "  ADR-007 是资源段用单数。确实要破例的话，把它加进 b-app/scripts/known-plural-paths.txt 并写清理由。",
      );
    }
    fixedPlural.splice(fixedPlural.indexOf(key), 1);
  }
  /*
   * `/common/**` 是跨端公共元数据（行业/主体/通道），C 端与 B 端要的是同一份。
   * 给它造一个 /biz 别名只会得到两条路径服务同一件事，而两条路径迟早返回不一样的东西。
   * 与 packages/shared/tests/type-alignment.test.ts 的豁免口径一致。
   */
  if (!ep.path.startsWith("/biz/") && !ep.path.startsWith("/common/")) {
    throw new Error(`端点 "${key}" 的路径 ${ep.path} 不在 /biz/** 下 —— B 端前缀见 ADR-007`);
  }
}
// 棘轮只准变短：清单里有、代码里已经改好的，要提醒删掉
if (fixedPlural.length) {
  console.error(`✗ 这些已经不是复数路径了，把它们从 known-plural-paths.txt 里删掉：\n  ${fixedPlural.join("\n  ")}`);
  process.exit(1);
}

// ---------------------------------------------------------------- 2. 类型 → JSON Schema
function collect(file, typeNames) {
  const gen = createGenerator({
    path: file,
    tsconfig: path.resolve(root, "tsconfig.json"),
    type: "*",
    skipTypeCheck: true,
    expose: "all",
    topRef: true,
    additionalProperties: false,
  });
  const out = {};
  for (const name of typeNames) {
    try {
      const s = gen.createSchema(name);
      Object.assign(out, s.definitions ?? {});
    } catch {
      // 该名字不是一个可导出的类型（如泛型别名），跳过 —— 由调用方决定要不要报错
    }
  }
  return out;
}

/** 从源码里抠出所有 `export interface X` / `export type X` 的名字 */
function exportedTypeNames(file) {
  const src = fs.readFileSync(file, "utf8");
  const names = [];
  const re = /^export\s+(?:interface|type)\s+(\w+)/gm;
  let x;
  while ((x = re.exec(src))) names.push(x[1]);
  return names;
}


const respNames = exportedTypeNames(typesFile);
const reqNames = exportedTypeNames(reqFile);

let schemas = { ...collect(typesFile, respNames), ...collect(reqFile, reqNames) };
if (!Object.keys(schemas).length) {
  console.error("没有抽出任何 schema，检查类型文件路径");
  process.exit(1);
}

schemas = JSON.parse(
  JSON.stringify(schemas).replaceAll("#/definitions/", "#/components/schemas/"),
);

// OpenAPI 组件名必须匹配 ^[a-zA-Z0-9.\-_]+$，泛型展开会产出 `Record<Lang,string>` 这种非法名
const renamed = {};
for (const name of Object.keys(schemas)) {
  if (/^[a-zA-Z0-9.\-_]+$/.test(name)) continue;
  renamed[name] = name.replace(/[^a-zA-Z0-9.\-_]+/g, "_").replace(/_+$/, "");
}
if (Object.keys(renamed).length) {
  let json = JSON.stringify(schemas);
  for (const [from, to] of Object.entries(renamed)) {
    for (const variant of [from, encodeURIComponent(from)]) {
      json = json.replaceAll(`#/components/schemas/${variant}`, `#/components/schemas/${to}`);
    }
  }
  schemas = JSON.parse(json);
  for (const [from, to] of Object.entries(renamed)) {
    schemas[to] = schemas[from];
    delete schemas[from];
  }
  console.log(`   已清洗 ${Object.keys(renamed).length} 个非法组件名（泛型展开）`);
}

/*
 * 下面两张表是**手写的**，TypeScript 管不到它们 —— 契约方法的返回类型没法在运行时反射。
 * 漏一条的后果是该端点的响应变成空 object：spec 看着完整，生成出的 DTO 是空壳。
 * 所以下面有一道校验：契约里的每个方法都必须在 RESPONSE_TYPES 里出现。
 */

/** 契约方法 → 响应类型名 */
const RESPONSE_TYPES = {
  /*
   * 2026-08-28 一次补齐 **97 条**。它们是逐批加进契约的，谁都没回来登记这张表 ——
   * 而漏一条整份 spec 就不生成（硬失败），于是 `openapi-b.yaml` 停在原地，
   * 且这个生成器**不在 check-generated-docs 的名单里**，没有任何东西会报。
   * 类型逐条取自 `contract.ts` 的签名，不是猜的。
   */
  mActivities: "StoreActivity[]",
  mActivity: "StoreActivity",
  mActivityConflicts: "ActivityConflict[]",
  mAddSpecDim: "SpecTemplate",
  mAddSpecValue: "SpecValueAdded",
  mAppointmentSlots: "AppointmentSlot[]",
  mArchiveSpecDim: "void",
  mCloseAppointmentSlot: "AppointmentSlot",
  mConfirmOfflinePay: "Order",
  mCountDetail: "StockCount",
  mCountFill: "void",
  mCountOpen: "string",
  mCountPost: "void",
  mCoupon: "MerchantCoupon",
  mCouponIssues: "CouponIssueBatch[]",
  mCoupons: "MerchantCoupon[]",
  mCreateMemberTag: "MemberTag",
  mDescribeGoods: "{ detail: string }",
  mDimValues: "SpecOption[]",
  mDropNoticeRecent: "StoreProfile",
  mEditMemberTag: "MemberTag",
  mEnrollMember: "Member",
  mEntities: "Entity[]",
  mEntity: "EntityStores",
  mEstateCounts: "Record<string, number>",
  mEstates: "EstateList",
  mFulfillmentImpact: "FulfillmentImpactItem[]",
  mGeoReverse: "GeoReverseResult",
  mGeoTips: "GeoTip[]",
  mInboundCreate: "string",
  mInboundPost: "void",
  mInboundUpdate: "void",
  mInboundVoid: "void",
  mIncomeSummary: "IncomeSummary",
  mIssueCoupon: "CouponIssueBatch",
  mLocationSetSource: "void",
  mMemberDetail: "MemberDetail",
  mMemberSegments: "MemberSegment[]",
  mMemberSettings: "MemberSetting",
  mMemberStats: "MemberStats",
  mMemberTags: "MemberTag[]",
  mMembers: "PageResult<Member>",
  mMergeMemberTag: "MemberMergePreview",
  mMySpecDims: "MerchantSpecDim[]",
  mMyStores: "EntityStores[]",
  mOpenAppointmentSlot: "AppointmentSlot",
  mOpenCommunityFromMap: "Community",
  mOutboundCreate: "string",
  mOutboundPost: "void",
  mOutboundVoid: "void",
  mPatchMember: "Member",
  mPeekCouponCode: "CouponRedeemView",
  mPickableDims: "SpecTemplate[]",
  mPickableProps: "SpecTemplate[]",
  mPickupCandidates: "PickupCandidate[]",
  mPlanReach: "ReachPlan",
  mPoster: "Poster",
  mPreviewMemberSegment: "MemberSegmentPreview",
  mQualifications: "MyQualifications",
  mQuickStart: "MerchantProfile",
  mRedeemCoupon: "CouponRedeemResult",
  mRegionPath: "Region[]",
  mRegionSearch: "RegionSearchResult",
  mRemoveMemberSegment: "void",
  mRenameSpecDim: "void",
  mSaveActivity: "StoreActivity",
  mSaveAnnouncement: "StoreProfile",
  mSaveCoupon: "MerchantCoupon",
  mSaveMemberSegment: "MemberSegment",
  mSaveMemberSettings: "MemberSetting",
  mSaveQualification: "Qualification",
  mSaveSpecOverride: "SpecTemplate[]",
  mSaveStoreFulfillment: "StoreFulfillment",
  mSelfBuildPickup: "PickupCandidate",
  mSendReach: "ReachResult",
  mSetActivityStatus: "StoreActivity",
  mSetCouponStatus: "MerchantCoupon",
  mSkuIdentityExport: "{ csv: string }",
  mSkuIdentityImport: "SkuIdentityReport",
  mSkuIdentityPlan: "SkuIdentityReport",
  mSpecProps: "SpecTemplate[]",
  mStockAdjust: "void",
  mStockBalances: "StockBalance[]",
  mStockDocuments: "StockDocument[]",
  mStockItem: "StockItemDetail",
  mStockLedger: "StockLedgerPage",
  mStockLocations: "StockLocation[]",
  mStockMonthly: "StockMonthly",
  mStockPickable: "StockBalance[]",
  mSupplierActive: "void",
  mSupplierCreate: "{ supplierNo: string }",
  mSupplierUpdate: "void",
  mSuppliers: "Supplier[]",
  mCarriers: "Carrier[]",
  mStockRanking: "StockRank[]",
  mStockSummary: "StockSummary",
  mStoreFulfillment: "StoreFulfillment",
  mStoreSpecDims: "StoreCategorySpecs[]",
  mTagMembers: "void",
  mTransferCreate: "string",
  mTransferDetail: "StockTransfer",
  mTransferReceive: "void",
  mTransferShip: "void",
  mVillageDict: "Region[]",
  mWarehouseCreate: "string",

  // 漏配一条就整份 spec 不生成（守卫是对的）：/biz/goods/{no}/store-stock 因此
  // 长期不在契约里，而后端实现了 —— 按契约算的覆盖率会凭空少一条。
  mSaveStoreStock: "Goods",
  mSaveStorePrice: "Goods",
  mSubmitGoods: "Goods",
  mSavePresale: "Goods",
  mSendOtp: "void",
  // 密码登录（并行改动带进来的两条）：设置只回成功与否，查询回一个布尔壳
  mSetPassword: "void",
  mHasPassword: "HasPasswordResp",
  mLogin: "MerchantLoginResp",
  mStaffLogin: "MerchantLoginResp",
  mProfile: "MerchantProfile",
  mApply: "MerchantProfile",
  mApplyDraft: "MerchantApplyReq",
  mMasterData: "MasterData",
  mPayments: "PaymentApplyment[]",
  mPayChannels: "PaymentApplyment[]",
  mSubmitPayment: "PaymentApplyment",
  mRefreshPayment: "PaymentApplyment",
  mStoreList: "Store[]",
  mCreateStore: "Store",
  mRenameStore: "Store",
  mSetStoreStatus: "Store",
  mSetDefaultStore: "Store",
  mSetStorePayment: "Store",
  mStoreCategories: "StoreCategory[]",
  mSaveStoreCategories: "StoreCategory[]",
  // 标准品搜索：从标准品建品的入口，一期只读
  mSpuStdSearch: "SpuStd[]",
  mStaffList: "MerchantStaff[]",
  mStaffLogs: "StaffLog[]",
  mRoles: "MerchantRole[]",
  mRolePerms: "PermOption[]",
  mCreateRole: "MerchantRole",
  mUpdateRole: "MerchantRole",
  mDeleteRole: "void",
  mVerifySearch: "Order[]",
  mAddStaff: "MerchantStaff",
  mSetStaffStatus: "MerchantStaff",
  mGrantStore: "MerchantStaff",
  mStore: "StoreProfile",
  mCommunities: "Community[]",
  mRegions: "Region[]",
  mApplyCommunity: "CommunityApply",
  mMyCommunityApplies: "CommunityApply[]",
  mSaveStore: "StoreProfile",
  mStoreQrcode: "StoreQrcode",
  mShareKit: "ShareKit",
  mTodo: "MerchantTodo",
  mStats: "MerchantStats",
  mCrossStoreOverview: "CrossStoreOverview",
  mCrossStoreCompare: "CrossStoreCompare",
  // 试用返回的是**开通后的新视图**，与读接口同一个类型 ——
  // 端上拿到就能重渲染，不必再拉一次
  mMyPlan: "MerchantPlan",
  mStartTrial: "MerchantPlan",

  // 消息与推送（触达域）。这五条一直没登记，而**漏一条整份 spec 就不生成** ——
  // 于是 `check:api` 全仓中断，连带别的域的契约校验也跑不了。
  // 类型逐字取自 contract.ts 的签名，不另猜。
  mMessageList: "Message[]",
  mMessageUnread: "number",
  mMessageRead: "Message[]",
  mMessageReadAll: "Message[]",
  mRegisterPushToken: "void",
  mUnregisterPushToken: "void",
  mGoodsList: "PageResult<Goods>",
  mGoodsDetail: "Goods",
  // 双版本（V279）。mGoodsDraft 的线上形状是 SaveGoodsReqBody（提交体镜像），
  // 不是页面的 GoodsDraft —— 无草稿时 data 为 null，由信封表达（同 mApplyDraft）
  mGoodsDraft: "SaveGoodsReqBody",
  mPublishPreview: "PublishPreview",
  mPublishGoods: "Goods",
  mDiscardGoodsDraft: "Goods",
  mSaveGoods: "Goods",
  mToggleGoods: "Goods",
  mSaveStock: "Goods",
  mUploadImage: "object",
  mRecognizeGoods: "GoodsGuess",
  mCategoryTree: "Category[]",
  mSpecTemplates: "SpecTemplate[]",
  mSaveSpecTemplate: "SpecTemplate",
  mOrderList: "PageResult<Order>",
  mOrderDetail: "Order",
  mShip: "Order",
  mDelivered: "Order",
  mDeliveryRule: "DeliveryRule",
  mSaveDeliveryRule: "DeliveryRule",
  mPickupOverview: "PickupOverview",
  mPickupOrders: "Order[]",
  mPickingList: "PickingRow[]",
  mMarkArrived: "Order[]",
  mVerify: "Order",
  mVerifyBatch: "VerifyBatchResult",
  mAfterSaleList: "Order[]",
  mApproveAfterSale: "Order",
  mRejectAfterSale: "Order",
  mConfirmReturn: "Order",
  mGroupList: "GroupBuy[]",
  mCreateGroup: "GroupBuy",
  mRequestList: "GroupRequest[]",
  mQuote: "GroupRequest",
  mReviewList: "Review[]",
  mReplyReview: "Review",
  mAppealReview: "Review",
  mCampaignList: "MarketingCampaign[]",
  mSaveCampaign: "MarketingCampaign",
  mToggleCampaign: "MarketingCampaign",
  mCustomers: "MerchantCustomer[]",
  mSettleList: "SettleBill[]",
  mBizScope: "BizScope",
  mRateCard: "RateCard",
  mPointsAccount: "MerchantPointAccount",
  mPointsRecords: "MerchantPointsRecord[]",
  mPointsToggle: "MerchantPointAccount",
  mReportShortage: "Order",
};

/** 契约方法 → 入参类型名。GET 的展开成 query 参数，POST 的作为 requestBody */
const REQUEST_TYPES = {
  mCrossStoreCompare: "CrossStoreCompareQuery",
  mLogin: "MerchantLoginReqBody",
  mStaffLogin: "StaffLoginReq",
  mApply: "MerchantApplyReqBody",
  mSubmitPayment: "SubmitPaymentReq",
  mCreateStore: "StoreEditReq",
  mRenameStore: "StoreEditReq",
  mSetStoreStatus: "SetActiveReq",
  mSetStorePayment: "SetStorePaymentReq",
  mAddStaff: "AddStaffReq",
  mSetStaffStatus: "SetActiveReq",
  mGrantStore: "GrantStoreReq",
  mSaveStore: "SaveStoreReqBody",
  mShareKit: "ShareKitQuery",
  mGoodsList: "GoodsListQuery",
  mSaveGoods: "SaveGoodsReqBody",
  mToggleGoods: "ToggleGoodsReq",
  mSaveStock: "SaveStockReq",
  mUploadImage: "UploadImageReq",
  mRecognizeGoods: "RecognizeGoodsReq",
  mSpecTemplates: "SpecTemplatesQuery",
  mSaveSpecTemplate: "SaveSpecTemplateReq",
  mOrderList: "OrderListQuery",
  mShip: "ShipReq",
  mSaveDeliveryRule: "SaveDeliveryRuleReqBody",
  mMarkArrived: "MarkArrivedReq",
  mVerify: "VerifyReq",
  mVerifyBatch: "VerifyBatchReq",
  mApproveAfterSale: "HandleAfterSaleReq",
  mRejectAfterSale: "HandleAfterSaleReq",
  mCreateGroup: "CreateGroupReq",
  mQuote: "QuoteReq",
  mReplyReview: "ReplyReviewReq",
  mAppealReview: "AppealReviewReq",
  mSaveCampaign: "SaveCampaignReqBody",
  mToggleCampaign: "ToggleCampaignReq",
  mReportShortage: "ReportShortageReq",
};

// 漏配 RESPONSE_TYPES 的端点会静默产出空 object —— 这里直接失败
const noResp = Object.keys(endpoints).filter((k) => !RESPONSE_TYPES[k]);
if (noResp.length) {
  console.error(`✗ 这些端点没配响应类型（会生成空 object）：${noResp.join(", ")}`);
  process.exit(1);
}
function queryParams(typeName) {
  const def = schemas[typeName];
  if (!def?.properties) return [];
  const required = new Set(def.required ?? []);
  return Object.entries(def.properties).map(([name, schema]) => ({
    name,
    in: "query",
    required: required.has(name),
    schema,
  }));
}

/**
 * 契约里的**标量**返回类型。
 *
 * 没有这张表的话，`"void"` / `"number"` 会走到最后一行，生成
 * `$ref: #/components/schemas/void` —— 一个**指向不存在组件的悬空引用**。
 * 校验器多半不报（$ref 解析是懒的），而拿这份 spec 生成客户端的人会得到一个
 * 编译不过的类型名，然后来问「后端返回的 void 是个什么对象」。
 */
const SCALARS = {
  void: { description: "无返回体（data 恒为 null）", nullable: true },
  number: { type: "number" },
  integer: { type: "integer" },
  string: { type: "string" },
  boolean: { type: "boolean" },
};

function dataSchema(typeExpr) {
  if (!typeExpr || typeExpr === "object") return { type: "object" };
  if (SCALARS[typeExpr]) return { ...SCALARS[typeExpr] };
  const arr = typeExpr.match(/^(\w+)\[\]$/);
  if (arr) return { type: "array", items: { $ref: `#/components/schemas/${arr[1]}` } };
  const page = typeExpr.match(/^PageResult<(\w+)>$/);
  if (page) {
    return {
      type: "object",
      properties: {
        records: { type: "array", items: { $ref: `#/components/schemas/${page[1]}` } },
        total: { type: "integer" },
        page: { type: "integer" },
        size: { type: "integer" },
      },
      required: ["records", "total", "page", "size"],
    };
  }
  return { $ref: `#/components/schemas/${typeExpr}` };
}


// ---------------------------------------------------------------- 3. 组装 OpenAPI
const paths = {};
for (const [key, ep] of Object.entries(endpoints)) {
  const oaPath = ep.path.replace(/:(\w+)/g, "{$1}");
  const reqType = REQUEST_TYPES[key];
  const params = [...ep.path.matchAll(/:(\w+)/g)].map((x) => ({
    name: x[1],
    in: "path",
    required: true,
    schema: { type: "string" },
  }));
  if (ep.method === "GET" && reqType) params.push(...queryParams(reqType));

  const op = {
    operationId: key,
    summary: ep.summary,
    tags: [oaPath.split("/")[2] ?? "biz"],
    security: ep.auth ? [{ bearerAuth: [] }] : [],
    parameters: params,
    responses: {
      200: {
        description: "OK",
        content: {
          "application/json": {
            // 统一响应包 Result<T> —— 全站口径，与 C 端/平台端一致
            schema: {
              type: "object",
              properties: {
                code: { type: "integer", description: "0 表示成功" },
                msg: { type: "string" },
                data: dataSchema(RESPONSE_TYPES[key]),
              },
              required: ["code", "msg", "data"],
            },
          },
        },
      },
    },
  };

  // PUT 与 POST 一样带 body。**此前抽取正则只认 GET|POST**，
  // 于是所有 PUT 端点（b-app 9 条、c-app 1 条）**静默不进 spec** ——
  // 规格看着完整，少的那几条谁也不会发现（同 endpoints 表那次「注释夹在中间」的坑）
  if ((ep.method === "POST" || ep.method === "PUT") && reqType) {
    op.requestBody = {
      required: true,
      content: {
        "application/json": { schema: { $ref: `#/components/schemas/${reqType}` } },
      },
    };
  }

  paths[oaPath] = { ...(paths[oaPath] ?? {}), [ep.method.toLowerCase()]: op };
}

/** key 只在必要时加引号 —— 裸 key 是最普通的写法，下游工具都按它匹配 */
const yamlKey = (k) => (/^[A-Za-z_][\w.-]*$/.test(k) ? k : JSON.stringify(k));

function toYaml(value, indent = 0) {
  const pad = "  ".repeat(indent);
  if (value === null || value === undefined) return "null";
  if (typeof value === "string") return JSON.stringify(value);
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) {
    if (!value.length) return "[]";
    return value.map((v) => `\n${pad}- ${toYaml(v, indent + 1).replace(/^\s+/, "")}`).join("");
  }
  const keys = Object.keys(value);
  if (!keys.length) return "{}";
  return keys
    .map((k) => {
      const v = toYaml(value[k], indent + 1);
      const inline = typeof value[k] !== "object" || value[k] === null;
      const isEmpty = v === "{}" || v === "[]";
      return `\n${pad}${yamlKey(k)}: ${inline || isEmpty ? v : v.startsWith("\n") ? v : `\n${"  ".repeat(indent + 1)}${v}`}`;
    })
    .join("");
}

const doc = {
  openapi: "3.1.0",
  info: {
    title: "ai-shop B 端 BFF",
    version: "0.1.0",
    license: { name: "UNLICENSED" },
    description:
      "由 b-app/src/api/endpoints.ts + api/requests.ts + packages/shared/types 自动生成，请勿手改。\n" +
      "生成命令：cd b-app && npm run gen:api\n\n" +
      "口径：响应包 {code,msg,data}，分页 {records,total,page,size}，camelCase，" +
      "单号 xxxNo，时间 xxxAt（UTC 毫秒），枚举大写下划线，金额为最小货币单位整数。",
  },
  servers: [{ url: "http://localhost:8080", description: "本地后端" }],
  components: {
    securitySchemes: { bearerAuth: { type: "http", scheme: "bearer" } },
    schemas,
  },
  paths,
};

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, `# 自动生成，请勿手改（npm run gen:api）${toYaml(doc)}\n`, "utf8");

const opCount = Object.values(paths).reduce((n, p) => n + Object.keys(p).length, 0);
const bodyCount = Object.values(paths)
  .flatMap((p) => Object.values(p))
  .filter((o) => o.requestBody).length;
console.log(`✅ ${outFile}`);
console.log(
  `   ${Object.keys(paths).length} 个路径 / ${opCount} 个操作 / ${Object.keys(schemas).length} 个 schema`,
);
console.log(`   入参：${bodyCount} 个 requestBody + ${Object.values(paths).flatMap((p) => Object.values(p)).filter((o) => o.parameters.some((x) => x.in === "query")).length} 个带 query 的操作`);
