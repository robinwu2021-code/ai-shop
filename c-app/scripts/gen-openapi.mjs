// 从「端点表 + TS 类型」生成 OpenAPI 3.1 规格，输出到 docs/api/openapi.yaml。
//
// 为什么值得做这一步：
//   后端目前还不存在。等它开写时，最省事的路径是**照着契约生成 controller 骨架**，
//   而不是让人对着前端代码手抄一遍接口 —— 手抄必然漂移，而且漂移只有在联调时才发现。
//   有了 openapi.yaml，后端可以直接跑 openapi-generator 出 Spring 的接口层。
//
// 数据流：
//   c-app/src/api/endpoints.ts（method/path/auth/summary）
//        +
//   packages/shared/src/types/index.ts（契约类型，C/B 两端共用）
//        → ts-json-schema-generator → JSON Schema
//        ↓
//   docs/api/openapi.yaml
//
// 运行：npm run gen:api
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { createGenerator } from "ts-json-schema-generator";

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, "..");
const outFile = path.resolve(root, "../docs/api/openapi.yaml");
// 类型已迁到 packages/shared —— C 端与 B 端共用同一份契约类型
const typesFile = path.resolve(root, "../packages/shared/src/types/index.ts");
// 入参类型（wire contract）单独一份 —— 见 ADR-007 §5
const reqFile = path.resolve(root, "src/api/requests.ts");

// ---------------------------------------------------------------- 1. 端点表
// endpoints.ts 是 TS 且 import 了 contract 的类型，直接 import 会牵进整棵类型树。
// 这里只要运行时的那份对象字面量，用正则把它抠出来即可 —— 结构简单且稳定。
const epSrc = fs.readFileSync(path.resolve(root, "src/api/endpoints.ts"), "utf8");
const body = epSrc.slice(
  epSrc.indexOf("export const ENDPOINTS"),
  epSrc.indexOf("/** 把 `:name` 替换成实际值 */"),
);

const endpoints = {};
const re =
  /(\w+):\s*\{\s*method:\s*"(GET|POST)",\s*path:\s*"([^"]+)",\s*auth:\s*(true|false),\s*summary:\s*"([^"]*)"/g;
let m;
while ((m = re.exec(body))) {
  endpoints[m[1]] = { method: m[2], path: m[3], auth: m[4] === "true", summary: m[5] };
}

// ---------------------------------------------------------------- 2. 类型 → JSON Schema
//
// ⚠️ 用 createSchema("*") 只会抽出「被别的类型引用到」的定义 —— requests.ts 里的接口
// 彼此不互相引用，于是一个都抽不出来（踩过：24 个请求类型只出了 5 个被 import 的枚举）。
// 所以改成**按名逐个生成**，把每次的 definitions 合并起来。
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

// ts-json-schema-generator 产出的内部引用是 JSON Schema 的 `#/definitions/X`，
// 而 OpenAPI 要求 `#/components/schemas/X`。不改写的话 spec 是**无效的** ——
// 校验器和 openapi-generator 都会在解析引用时失败。
schemas = JSON.parse(
  JSON.stringify(schemas).replaceAll("#/definitions/", "#/components/schemas/"),
);

// OpenAPI 的组件名必须匹配 ^[a-zA-Z0-9.\-_]+$，而泛型类型会生成出
// `Record<Lang,string>` 这种名字 —— 带尖括号和逗号，spec 直接非法。
// 这里统一清洗，并同步改写所有指向它的 $ref。
const renamed = {};
for (const name of Object.keys(schemas)) {
  if (/^[a-zA-Z0-9.\-_]+$/.test(name)) continue;
  renamed[name] = name.replace(/[^a-zA-Z0-9.\-_]+/g, "_").replace(/_+$/, "");
}
if (Object.keys(renamed).length) {
  let json = JSON.stringify(schemas);
  for (const [from, to] of Object.entries(renamed)) {
    // 生成器写 $ref 时会对名字做 URL 编码（`Record<Lang,string>` →
    // `Record%3CLang%2Cstring%3E`），所以两种形式都要改，否则引用解析不到。
    for (const variant of [from, encodeURIComponent(from)]) {
      json = json.replaceAll(
        `#/components/schemas/${variant}`,
        `#/components/schemas/${to}`,
      );
    }
  }
  schemas = JSON.parse(json);
  for (const [from, to] of Object.entries(renamed)) {
    schemas[to] = schemas[from];
    delete schemas[from];
  }
  console.log(`   已清洗 ${Object.keys(renamed).length} 个非法组件名（泛型展开）`);
}

// ---------------------------------------------------------------- 3. 组装 OpenAPI
/** 契约方法 → 响应类型名。手工维护，因为 TS 的返回类型没法在运行时反射出来 */
const RESPONSE_TYPES = {
  confirmGroupBatch: "Order[]",
  fillReturnExpress: "Order",
  frequentItems: "FrequentItem[]",
  groupPickupOrders: "Order[]",
  myHostedGroups: "GroupBuy[]",
  myStores: "Merchant[]",
  promotedGoods: "Goods[]",
  promotedMerchants: "Merchant[]",
  raiseDispute: "Order",
  reorderFrom: "ReorderResult",
  storeHome: "StoreHome",
  // boolean 没有对应 schema，用 object 兜底并在此说明，避免下次又有人以为是漏配
  toggleFavoriteStore: "object",
  verifyGroupPickup: "Order",
  login: "LoginResp",
  profile: "User",
  bindCommunity: "User",
  addressList: "Address[]",
  saveAddress: "Address[]",
  removeAddress: "Address[]",
  setDefaultAddress: "Address[]",
  nearbyCommunities: "Community[]",
  goodsList: "PageResult<Goods>",
  goodsDetail: "Goods",
  cartList: "CartItem[]",
  cartAdd: "CartItem[]",
  cartUpdate: "CartItem[]",
  cartRemove: "CartItem[]",
  createOrder: "Order",
  payOrder: "Order",
  orderList: "PageResult<Order>",
  orderDetail: "Order",
  cancelOrder: "Order",
  applyAfterSale: "Order",
  couponList: "Coupon[]",
  receiveCoupon: "Coupon",
  groupBuyList: "GroupBuy[]",
  groupBuyDetail: "GroupBuy",
  joinGroupBuy: "GroupBuy",
  createGroupBuy: "GroupBuy",
  requestList: "GroupRequest[]",
  requestDetail: "GroupRequest",
  createRequest: "GroupRequest",
  toggleInterest: "GroupRequest",
  chooseQuote: "GroupRequest",
  confirmRequest: "GroupRequest",
  merchantList: "Merchant[]",
  merchantDetail: "Merchant",
  visitedMerchants: "VisitedMerchant[]",
  merchantApply: "object",
  reviewList: "Review[]",
  createReview: "Review",
  toggleReviewLike: "Review",
  pointAccount: "PointAccount",
  pointRecords: "PointRecord[]",
  merchantPointAccount: "PointAccount",
  merchantPointRecords: "PointRecord[]",
  myCards: "UserCard[]",
  messageList: "Message[]",
  readMessage: "Message[]",
  readAllMessages: "Message[]",
};

/** 契约方法 → 入参类型名。GET 的展开成 query 参数，POST 的作为 requestBody */
const REQUEST_TYPES = {
  login: "LoginReqBody",
  bindCommunity: "BindCommunityReq",
  saveAddress: "SaveAddressReq",
  nearbyCommunities: "NearbyQuery",
  goodsList: "GoodsListQuery",
  cartAdd: "CartAddReq",
  cartUpdate: "CartUpdateReq",
  cartRemove: "CartRemoveReq",
  createOrder: "CreateOrderReqBody",
  orderList: "OrderListQuery",
  promotedGoods: "PromotedGoodsQuery",
  promotedMerchants: "PromotedMerchantsQuery",
  applyAfterSale: "AfterSaleReq",
  groupBuyList: "GroupBuyListQuery",
  joinGroupBuy: "JoinGroupBuyReq",
  createGroupBuy: "CreateGroupBuyReq",
  requestList: "RequestListQuery",
  createRequest: "CreateRequestReq",
  chooseQuote: "ChooseQuoteReq",
  merchantList: "MerchantListQuery",
  merchantApply: "MerchantApplyReq",
  reviewList: "ReviewListQuery",
  createReview: "CreateReviewReq",
};

/*
 * 漏配 RESPONSE_TYPES 的端点会静默产出 `data: {type:"object"}` —— spec 看着完整，
 * 生成出来的 DTO 是空壳，后端照着实现就得自己猜返回什么。
 * 这事已经发生过：13 个方法（门店主页、再来一单、发起人签收/核销、退货运单号、上升平台…）
 * 先后被两条线加进契约，谁都没补这张表，而生成器一声不吭。现在改成直接失败。
 */
const missingResp = Object.keys(endpoints).filter((k) => !RESPONSE_TYPES[k]);
if (missingResp.length) {
  console.error(`✗ 这些端点没配响应类型（会生成空 object）：${missingResp.join(", ")}`);
  process.exit(1);
}

/** GET 的入参展开成 query 参数列表（OpenAPI 不允许 GET 带 requestBody） */
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

function dataSchema(typeExpr) {
  if (!typeExpr || typeExpr === "object") return { type: "object" };
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

/*
 * 这两张映射表是**手写的**，TypeScript 管不到它们。
 * 之前 leaderApply 指向的 LeaderApplyReq 随团长端点迁往 b 端一起被删了，
 * 生成器却一声不响地产出了一个「没有入参」的接口 —— 契约与实现就是这么漂移的。
 * 所以在这里两个方向都卡死：键必须是真端点，值必须是真类型。
 */
for (const [table, name] of [
  [RESPONSE_TYPES, "RESPONSE_TYPES"],
  [REQUEST_TYPES, "REQUEST_TYPES"],
]) {
  for (const key of Object.keys(table)) {
    if (!endpoints[key]) {
      throw new Error(`${name} 里的 "${key}" 不是端点表中的端点 —— 端点删了或改名了，映射没跟上`);
    }
  }
}
for (const [key, typeName] of Object.entries(REQUEST_TYPES)) {
  if (!schemas[typeName]) {
    throw new Error(`REQUEST_TYPES["${key}"] 指向的类型 ${typeName} 不存在 —— 入参会被静默丢掉`);
  }
}

/*
 * 带 id 的资源段一律**单数**：`/mp/order/{orderNo}`，不是 `/mp/orders/{orderNo}`。
 * 曾有三条端点误写成复数，前后端各写各的、编译期毫无提示，只有联调 404 才会发现。
 * 末端集合（`/mp/groups/{groupNo}/orders` 里的 orders）是复数，那是对的 —— 只卡「复数 + 紧跟 id」。
 */
for (const [key, ep] of Object.entries(endpoints)) {
  const bad = ep.path.match(/\/(\w+s)\/:/);
  // goods 是不可数名词（单复同形），address 本来就以 s 结尾 —— 都不是复数
  if (bad && !/^(goods|address)$/.test(bad[1])) {
    throw new Error(
      `端点 "${key}" 的路径 ${ep.path} 用了复数资源名 "${bad[1]}" 且紧跟 id —— 统一用单数`,
    );
  }
}

const paths = {};
for (const [key, ep] of Object.entries(endpoints)) {
  // OpenAPI 用 {name}，端点表用 :name
  const oaPath = ep.path.replace(/:(\w+)/g, "{$1}");
  const pathParams = [...ep.path.matchAll(/:(\w+)/g)].map((x) => ({
    name: x[1],
    in: "path",
    required: true,
    schema: { type: "string" },
  }));

  const reqType = REQUEST_TYPES[key];
  const params = [...pathParams];
  if (ep.method === "GET" && reqType) params.push(...queryParams(reqType));

  const op = {
    operationId: key,
    summary: ep.summary,
    tags: [oaPath.split("/")[2] ?? "misc"],
    security: ep.auth ? [{ bearerAuth: [] }] : [],
    parameters: params,
    responses: {
      200: {
        description: "OK",
        content: {
          "application/json": {
            // 统一响应包 Result<T> —— 全站口径，后端也必须这么返
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

  if (ep.method === "POST" && reqType) {
    op.requestBody = {
      required: true,
      content: {
        "application/json": { schema: { $ref: `#/components/schemas/${reqType}` } },
      },
    };
  }

  paths[oaPath] = { ...(paths[oaPath] ?? {}), [ep.method.toLowerCase()]: op };
}

const doc = {
  openapi: "3.1.0",
  info: {
    title: "ai-shop C 端 BFF",
    version: "0.1.0",
    license: { name: "UNLICENSED" },
    description:
      "由 c-app/src/api/endpoints.ts + api/requests.ts + packages/shared/types 自动生成，请勿手改。\n" +
      "生成命令：cd c-app && npm run gen:api\n\n" +
      "口径：响应包 {code,msg,data}，分页 {records,total,page,size}，" +
      "camelCase，单号 xxxNo，时间 xxxAt（UTC 毫秒），枚举大写下划线，" +
      "金额为最小货币单位整数。禁止 delete*，软删除用 archive*。",
  },
  servers: [{ url: "http://localhost:8080", description: "本地后端" }],
  components: {
    securitySchemes: {
      bearerAuth: { type: "http", scheme: "bearer", description: "C 池 Bearer，仅属主鉴权，无 RBAC" },
    },
    schemas,
  },
  paths,
};

// ---------------------------------------------------------------- 4. 输出 YAML
/** 安全的裸 key：字母数字下划线连字符点，且不以数字开头。其余（含 `/mp/x` 这类路径）加引号 */
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
      // key **只在必要时加引号**。之前无条件 JSON.stringify(k)，于是连 `paths:` 都写成
      // `"paths":` —— 合法 YAML，但没人这么写，下游按 `^paths:` 匹配的工具一条也读不到。
      // backend/scripts/api-align.py 就是这么被静默瘸掉的：它报「契约 0 条、覆盖率 0%」，
      // 而那正是一个专门用来发现漂移的守卫。生成物要照最普通的写法出，别让下游去迁就。
      const v = toYaml(value[k], indent + 1);
      const inline = typeof value[k] !== "object" || value[k] === null;
      const isEmpty = v === "{}" || v === "[]";
      return `\n${pad}${yamlKey(k)}: ${inline || isEmpty ? v : v.startsWith("\n") ? v : `\n${"  ".repeat(indent + 1)}${v}`}`;
    })
    .join("");
}

fs.mkdirSync(path.dirname(outFile), { recursive: true });
fs.writeFileSync(outFile, `# 自动生成，请勿手改（npm run gen:api）${toYaml(doc)}\n`, "utf8");

const opCount = Object.values(paths).reduce((n, p) => n + Object.keys(p).length, 0);
console.log(`✅ ${outFile}`);
const withBody = Object.values(paths).reduce(
  (n, p) => n + Object.values(p).filter((o) => o.requestBody).length,
  0,
);
const withQuery = Object.values(paths).reduce(
  (n, p) => n + Object.values(p).filter((o) => o.parameters?.some((x) => x.in === "query")).length,
  0,
);
console.log(`   ${Object.keys(paths).length} 个路径 / ${opCount} 个操作 / ${Object.keys(schemas).length} 个 schema`);
console.log(`   入参：${withBody} 个 requestBody + ${withQuery} 个带 query 的操作`);
