// 枚举对账 · **按字段**比对取值域。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么需要它，而 check-enums.mjs 不够
// ─────────────────────────────────────────────────────────────────────────────
// check-enums.mjs 的判定是：
//     全集 = 后端出现过的所有大写字面量
//     报错 = 端上声明的值 ∉ 全集
//
// 这只能抓「端上编了一个后端根本没有的词」。它按原理抓不到最伤人的一类：
// **两边都有词，但不是同一个词。**
//
// 实测过两次，形状一模一样：
//   · 端上 FULFILLMENT.PICKUP   = "PICKUP"          库里是 "STORE_PICKUP"
//   · 端上 FULFILLMENT.DELIVERY = "DELIVERY"        库里是 "MERCHANT_DELIVERY"
// 两个词在全集里都存在（因为 STORE_PICKUP / MERCHANT_DELIVERY 本身就在），
// 于是比对通过 —— 而确认订单页把 `fulfillment.MERCHANT_DELIVERY` 原样打给了用户：
// 词条按端上的叫法建，后端下发库里的值，查不到就回退成键名。
//
// 换成按字段比对，同一个问题立刻现形：在 `ord_sub_order.fulfillment` 这**一个字段**
// 上，端上的集合是 {STORE_PICKUP, DELIVERY, ...}、后端是 {STORE_PICKUP,
// MERCHANT_DELIVERY, ...} —— 两个集合根本不相等。
//
// ─────────────────────────────────────────────────────────────────────────────
// 两个方向都要报
// ─────────────────────────────────────────────────────────────────────────────
//   端上有、后端没有 → 端上按它筛必然是空列表（订单状态那次就是这样）
//   后端有、端上没有 → 后端下发时端上落进兜底分支或显示键名（履约那两次）
// 旧工具只查前一个方向。第二个方向才是同物异名的另一半，漏了就只能靠人点开页面。
//
// ─────────────────────────────────────────────────────────────────────────────
// 对应关系必须手写
// ─────────────────────────────────────────────────────────────────────────────
// 「shared 的 FULFILLMENT 对应 ord_sub_order.fulfillment」这件事推断不出来 ——
// 名字不一样、文件也不在一起。所以下面 FIELDS 是一张**显式声明表**。
// 这不是缺陷，是这类工具能成立的前提：不写下来，就没有任何东西知道谁该等于谁。
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
// DDL 解析只有一份 —— 见 ddl.mjs 的文件头「为什么必须只有一份」
import { readSchema, MIGRATION_DIR, INVENTORY_MIGRATION_DIR } from "./lib/ddl.mjs";

/**
 * 重放后的表结构，**惰性 + 只算一次**：`audit()` 会对每条 FIELDS 查一次列注释，
 * 每次都重放一遍全部迁移的话，200 多个文件要读上十几遍。
 */
let SCHEMA = null;
function schema() {
  if (!SCHEMA) SCHEMA = readSchema(ROOT, [MIGRATION_DIR, INVENTORY_MIGRATION_DIR]);
  return SCHEMA;
}

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");
/* **目录，不是文件**：那份实体单文件按域拆开了，指向 index.ts 只会读到一份门面 */
const SHARED_TYPES = "packages/shared/src/types";
const SHARED_CONST = "packages/shared/src/utils/constants/index.ts";

/**
 * 一个 wire 字段一条。
 *
 * backend 取值域三种来源，按可靠性排序：
 *   javaConst —— Java 常量类里的 `public static final String X = "..."`，最可靠
 *   ddl       —— 建表语句的列注释里斜杠分隔的取值域
 *   literal   —— 直接写死。**必须给 why**，否则这张表会退化成「报错了就往里加」
 *
 * clients 里 `planned` 指向一个白名单常量：后端还没实现的值列在那里，
 * 不当作缺陷报。**同物异名与「后端未实现」危害完全不同**，混在一起就没法自动判定。
 */
export const FIELDS = [
  {
    concept: "订单状态（下发口径）",
    field: "ord_sub_order.status → OrderStatusView",
    backend: { javaConst: "shop-core/src/main/java/ai/neargo/shop/trade/service/OrderStatusView.java" },
    clients: [
      { file: SHARED_TYPES, type: "OrderStatus" },
      { file: "ops-web/lib/types/order.ts", type: "OrderStatus" },
    ],
  },
  {
    concept: "售后单状态",
    field: "ord_after_sale.status",
    backend: {
      javaConst: "shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdAfterSale.java",
      // 同一个类里还放着 type 列的取值（REFUND_ONLY 等），按名字排掉
      only: ["APPLIED", "REFUNDING", "REFUNDED", "REJECTED", "ARBITRATING", "CLOSED"],
    },
    clients: [
      { file: SHARED_TYPES, type: "AfterSaleStatus" },
      { file: "ops-web/lib/types/aftersale.ts", type: "AfterSaleStatus" },
    ],
  },
  {
    concept: "履约方式",
    field: "ord_sub_order.fulfillment",
    backend: {
      // 取值域搬到了 base：商品域与交易域都要用它，而商品域不能依赖交易域。
      // OrdSubOrder 那四个常量现在只是引用（= Fulfillments.X），
      // 继续指着它会**提不出任何字面量** —— 那正是这条 fatal 断言拦下来的情况。
      javaConst: "shop-base/src/main/java/ai/neargo/shop/common/Fulfillments.java",
      // STORE_VERIFY / APPOINTMENT 于 2026-08-17 接通（服务履约一、二期）。
      // INSTANT 未接，由端上的 PLANNED_FULFILLMENTS 申报，不在这里列
      only: ["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS",
        "STORE_VERIFY", "APPOINTMENT"],
    },
    clients: [
      { file: SHARED_CONST, const: "FULFILLMENT", planned: "PLANNED_FULFILLMENTS" },
      // 漏登过一次：ops-web 因此多了一个后端没有的 SERVICE，而对账一路绿灯
      { file: "ops-web/lib/types/order.ts", type: "FulfillmentType", plannedValues: [] },
    ],
  },
  {
    concept: "五品类（商品形态）",
    field: "prd_goods.type",
    backend: { ddl: ["prd_goods", "type"] },
    clients: [{ file: SHARED_CONST, const: "CATEGORY_TYPE" }],
  },
  {
    /*
     * 登记这一条是**为了钉住上面 ddlValues 那段注释里的教训**：
     * 它曾经读的是建表时那份旧注释 `PENDING/RECEIVED`，而这一列早被
     * `MODIFY COLUMN` 改成了 PLANNED/DISPATCHED/ARRIVED/SIGNED，两套词零重合。
     * 端上（ops-web 的 BatchStatus）一直是对的四个。
     * 也就是说：在解析器修好之前，谁登记这一列，谁就会拿到四条方向全反的「差异」。
     * 现在两侧逐字相等 —— 这条既是登记，也是那次修复的回归用例。
     */
    concept: "履约批次状态",
    field: "ful_batch.status",
    backend: { ddl: ["ful_batch", "status"] },
    clients: [{ file: "ops-web/lib/types/fulfillment.ts", type: "BatchStatus" }],
  },
  {
    concept: "售后原因",
    field: "ord_after_sale.reason",
    /*
     * 走 javaConst 不走 ddl：`ord_after_sale.reason` 那一列**没有列注释**，
     * 取值域只存在于 Java 侧。收编前它是 AfterSaleServiceImpl 里的裸 `List.of`，
     * 正因为不是 `static final String` 才登记不进来 —— 2026-09-06 提成
     * OrdAfterSale.REASON_* 之后才登得上。
     *
     * only 是必须的：这个实体上并排放着三组取值域（status / type / reason），
     * 不筛的话三组会混成一堆，与端上任何一个类型都对不上。
     */
    backend: {
      javaConst: "shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdAfterSale.java",
      only: ["NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER"],
    },
    clients: [{ file: SHARED_TYPES, type: "AfterSaleReason" }],
  },
  {
    concept: "商家经营状态",
    field: "mch_entity.status",
    backend: { ddl: ["mch_entity", "status"] },
    clients: [{ file: "ops-web/lib/types/merchant.ts", type: "MerchantStatus" }],
    // shared 的 MerchantStatus 是 B 端的**合并视图**（经营 × 审核 两张表合成一个
    // 「我现在能不能做生意」），不是这个字段的镜像 —— 见 skip 注释
  },
  {
    concept: "入驻审核状态",
    field: "mch_entity_apply.status",
    backend: { ddl: ["mch_entity_apply", "status"] },
    clients: [{ file: "ops-web/lib/types/merchant.ts", type: "ApplyStatus" }],
  },
  {
    concept: "营销活动类型",
    field: "mkt_campaign.type",
    backend: {
      javaConst: "shop-core/src/main/java/ai/neargo/shop/marketing/campaign/entity/MktCampaign.java",
      only: ["COUPON", "FULL_CUT", "FLASH", "BUY_GIFT"],
    },
    clients: [
      { file: SHARED_TYPES, type: "CampaignType" },
      { file: "ops-web/lib/types/marketing.ts", type: "CampaignType" },
    ],
  },
  {
    concept: "自提点类型",
    field: "cmt_pickup_point.type",
    backend: { ddl: ["cmt_pickup_point", "type"] },
    clients: [
      { file: SHARED_TYPES, type: "PickupPointType" },
      { file: "ops-web/lib/types/community.ts", type: "PickupPointType" },
    ],
  },
  {
    /*
     * 2026-09-09 从 known-unregistered-value-domains.txt 摘下来的第一条。
     *
     * 后端一直有正经的 `InvEnums.TransferStatus`（四个常量），端上却是
     * `status: string` + 一句注释写取值 —— 而注释里只写了三个，漏了 VOIDED，
     * 页面却已经在比较它。裸字面量比较有 7 处（在途角标、数量、操作条都挂在
     * SHIPPED 上），拼错一个字母不报错，那个分支从此不进。
     *
     * ops-web 侧没有调拨页面，所以 clients 只有一条 —— 不是漏登。
     */
    concept: "调拨单状态",
    field: "inv_transfer_order.status",
    backend: {
      javaConst: "shop-inventory/src/main/java/ai/neargo/shop/inventory/support/InvEnums.java",
      // InvEnums 是一个装了十几个取值域的壳（ReservationStatus / MasterStatus / …），
      // 不按名字排掉的话会把整个文件的常量都当成这一列的取值域
      only: ["DRAFT", "SHIPPED", "RECEIVED", "VOIDED"],
    },
    clients: [{ file: SHARED_TYPES, type: "TransferStatus" }],
  },
];

/**
 * 显式不比对的项。每条都要写清楚**为什么这个差异是对的**，
 * 而不是「暂时先放过」—— 后者会让这张表变成垃圾场（豁免名单静音过一次真 bug，
 * 见 docs/technical/design/枚举统一方案.md §0）。
 */
const INTENTIONAL = new Map([
  [
    "ops-web:CampaignType",
    "**重名但不是同一个东西**：ops-web 的 Campaign 是平台投放场次（带 position，" +
      "秒杀场按投放位置分组做重叠校验），由运营建；mkt_campaign 是店铺级活动" +
      "（entity_no NOT NULL，不跨店），由商家建。后端确无对应表。" +
      "按 mkt_campaign 那套改名等于把两个概念合并成一个 —— 见 " +
      "ops-web/lib/types/marketing.ts 的注释与 docs/technical/archive/营销枚举对账报告.md §1③。",
  ],
  [
    "shared:MerchantStatus",
    "B 端的合并视图：库里坚持把经营(mch_entity.status)与审核(mch_entity_apply.status)" +
      "分成两张表（驳回一份申请 ≠ 封禁一家店，操作人、审计口径、可逆性全不同），" +
      "而 B 端首页要在一个地方回答「我现在能不能做生意」。NONE/APPLYING 是端上派生的词。" +
      "**这个映射本身该有名字有测试**（阶段三 3.1），而不是靠调用方自己 switch。",
  ],
]);

// ─────────────────────────────────────────────────────────────────────────────

/** 取 Java 常量类里的 `public static final String X = "值";` */
function javaConstValues(rel, only) {
  const src = readFileSync(join(ROOT, "backend", rel), "utf8");
  const out = [];
  for (const m of src.matchAll(/static final String \w+\s*=\s*"([A-Z][A-Z0-9_]*)"/g)) {
    out.push(m[1]);
  }
  return only ? out.filter((v) => only.includes(v)) : out;
}

/**
 * 取某一列的取值域（来自它**当前**的列注释）。
 *
 * <p><b>解析走 `scripts/lib/ddl.mjs`，不自己写。</b>2026-09-06 这里曾手写一份
 * CREATE TABLE 解析，与 {@link surface} 那份是同一天被换掉的第二处 ——
 * 换 surface 时漏了它，而它是**活路径**：`FIELDS` 里 `backend: { ddl: [...] }`
 * 那几条走的就是它。三个缺陷同样在：
 *
 * <ul>
 *   <li>收尾写死 `) ENGINE` —— 195 张表里有 67 张找不到（合法的 `) COMMENT='…';`）；</li>
 *   <li>只扫平台迁移目录 —— 进销存那条独立 Flyway 历史读不到；</li>
 *   <li><b>不重放 `ALTER`</b> —— 这一条最要紧，因为它**不报错、直接给错答案**：
 *       列注释被后续 `MODIFY COLUMN` 改过时读到的是建表时那份旧的。
 *       实测 `ful_batch.status`：旧注释 `PENDING/RECEIVED`，实际
 *       `PLANNED/DISPATCHED/ARRIVED/SIGNED` —— <b>两套词零重合</b>。
 *       而 ops-web 的 `BatchStatus` 正是后面这四个，也就是端上对、判据错。
 *       谁要是登记了这一列，这道闸门会把端上四个全报成「后端不认的词」、
 *       把旧的两个报成「端上缺的」，四条全反，而且报得很确定 ——
 *       一个会给出确定错答案的工具，比没有这个工具更糟。</li>
 * </ul>
 */
function ddlValues(table, column) {
  const def = schema().get(table);
  if (!def) {
    return { error: `找不到 ${table} 的建表语句（重放全部迁移之后仍然没有这张表）` };
  }
  const col = (def.cols ?? []).find((c) => c.name === column);
  if (!col) {
    return { error: `${table} 里没有 ${column} 这一列` };
  }
  if (!col.comment) {
    return { error: `${table}.${column} 没有列注释，取值域无处可查` };
  }
  const vals = valuesInComment(col.comment);
  if (!vals.length) {
    return { error: `${table}.${column} 的注释里没有取值域：${col.comment}` };
  }
  return { values: vals };
}

/** 端上：字面量联合类型 `export type X = "A" | "B";` */
function unionValues(src, name) {
  const m = src.match(new RegExp(`export type ${name}\\s*=\\s*((?:[^;]*?"[^"]+"[^;]*?)+);`));
  if (!m) return null;
  return [...m[1].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
}

/** 端上：常量对象 `export const X = { A: "a" } as const;` */
function constValues(src, name) {
  const m = src.match(new RegExp(`export const ${name} = \\{([^}]*)\\} as const;`));
  if (!m) return null;
  return [...m[1].matchAll(/:\s*"([^"]+)"/g)].map((x) => x[1]);
}

/** 白名单数组 `export const X: readonly string[] = [A.B, ...];` —— 取引用到的常量值 */
function plannedValues(src, name, ownerConst) {
  const m = src.match(new RegExp(`export const ${name}[^=]*=\\s*\\[([^\\]]*)\\]`));
  if (!m) return [];
  const owner = new Map();
  const om = src.match(new RegExp(`export const ${ownerConst} = \\{([^}]*)\\} as const;`));
  if (om) for (const e of om[1].matchAll(/(\w+):\s*"([^"]+)"/g)) owner.set(e[1], e[2]);
  return [...m[1].matchAll(new RegExp(`${ownerConst}\\.(\\w+)`, "g"))]
    .map((x) => owner.get(x[1]))
    .filter(Boolean);
}

/**
 * 显式驳回：看过了，这一列<b>不是</b>受限取值域。
 *
 * <p>与「还没人看过」必须在数据里分得开 —— 把没看过的显示成没问题，
 * 比没有登记表更危险（同 `enum-registry.ts` 的 `UNREVIEWED` 那条教训）。
 *
 * <p>今天这里只有一类：**候选启发式自己的误报**。列注释里出现斜杠分隔的大写词
 * 不一定是取值域，日期格式（`YYYY/MM/DD`）长得一模一样。
 * 业务层面的「这一列不是受限取值域」要由做登记的人写，不要替他判 ——
 * 那是 §3 待业务拍板的第 4 条（驳回理由谁把关）。
 */
export const DISMISSED = [
  { key: "stl_settle_invoice.period", why: "日期格式 YYYY/MM，不是取值域 —— 候选启发式的误报" },
  { key: "ful_batch.arrive_date", why: "日期格式 YYYY/MM/DD，同上" },
  { key: "stl_recon_diff.bill_date", why: "日期格式 YYYY/MM/DD，同上" },
  { key: "mch_entity_apply.community_nos", why: "JSON 数组列，注释里的 JSON/COMMUNITY 是句子不是取值域" },
];

/**
 * 代码侧可见面：建表注释里枚举了取值的列。
 *
 * <p>⚠️ **这不是覆盖率的分母。** 分母应当是需求端定义的领域对象及其取值域
 * （见 TDD-取值域按字段对账-补全登记 §2.2），而那份定义今天基本不存在
 * （17 个 L2 域里只有三处写全）。这里扫出来的是**另一半** ——
 * 「代码里实际有什么」。拿它当分母会得到一个自证的覆盖率：
 * 分母是自己扫出来的，需求里有、代码没实现的那一类永远不在里面。
 *
 * <p>那它有什么用：让「已登记 N 个」旁边有一个可点名的清单，
 * 而不是一个谁都不知道有多大的空白。
 * （这里不写具体数字：CLI 里那句刚从写死的 8 改成 `FIELDS.length`，
 *   而这条注释还停在 8 —— 手抄的数字在注释里一样会漂，只是没人跑它。）
 *
 * <p><b>解析走 `scripts/lib/ddl.mjs`，不自己写。</b>2026-09-06 第一版在这里手写了
 * 第四份 CREATE TABLE 解析，把那份共用解析器早就修好的三个缺陷原样重现了一遍：
 * 收尾写死 `) ENGINE`（14 张表用的是合法的 `) COMMENT='…';`，整张匹配不上）、
 * 只扫平台迁移目录（进销存是另一条 Flyway 历史）、只读建表体不重放
 * `ADD COLUMN` / `MODIFY COLUMN`（后加的列与改过的列注释全看不见）。
 * 后果不是报错是**静默少数**：可见面报 134，实际 206，少 39%，
 * 而这个数正是下面棘轮与 `known-unregistered-value-domains.txt` 的依据。
 * ddl.mjs 的文件头写着这套逻辑曾在三个脚本里各写一遍、「修一处等于漏两处」——
 * 那一版是第四份。
 */
export function surface() {
  const tables = schema();

  /*
   * 扫描面的下界。**没有这一条，解析器哪天读不到东西，这里会安静地返回空 Map** ——
   * 未判定面变成 0、棘轮里每一行都成了「已判定完」，一片绿。
   * 这就是本仓库反复付过代价的形状：「找出违规」型判据，少扫等于全绿。
   */
  if (tables.size < 150) {
    throw new Error(
      `DDL 只解析出 ${tables.size} 张表（下界 150）—— 解析器或迁移目录出问题了。`
        + "不抛的话可见面会静默变空，未判定面跟着归零，而那看起来像是活干完了。",
    );
  }

  const out = new Map();
  for (const [table, def] of tables) {
    for (const col of def.cols ?? []) {
      const vals = valuesInComment(col.comment ?? "");
      if (vals.length >= 2) out.set(`${table}.${col.name}`, vals);
    }
  }
  return out;
}

/**
 * 列注释里的取值域。两种写法都要认（与 {@link ddlValues} 同一套判据）：
 *   `A/B/C：说明`      —— 取值挤在开头
 *   `A=说明 / B=说明`  —— 每个取值自带说明
 */
function valuesInComment(comment) {
  const eq = [...comment.matchAll(/\b([A-Z][A-Z0-9_]+)\s*=/g)].map((x) => x[1]);
  if (eq.length) return eq;
  return [...comment.split(/[：:，,（(]/)[0].matchAll(/([A-Z][A-Z0-9_]{1,})/g)].map((x) => x[1]);
}

/** 已判定 = 登记进 FIELDS ∪ 显式驳回 */
function judged() {
  const out = new Set(DISMISSED.map((d) => d.key));
  for (const f of FIELDS) out.add(f.field.split(/\s*(?:→|@)\s*/)[0].trim());
  return out;
}

/**
 * 未判定：代码侧可见、既没登记也没驳回的列。**点名，不只给个数。**
 *
 * <p>只给总数的话，「126 个未判定」这句话没有任何人能据它做下一步 ——
 * 而点名之后它就是一份可以逐条消化的清单。
 */
export function uncovered() {
  const seen = judged();
  return [...surface().entries()]
    .filter(([key]) => !seen.has(key))
    .map(([key, values]) => ({ key, values }))
    .sort((a, b) => (a.key < b.key ? -1 : 1));
}

/** 未判定清单的棘轮基线：只准变短。文件头写着它为什么不是分母。 */
export const RATCHET_FILE = "known-unregistered-value-domains.txt";

/**
 * 与基线比对。
 *
 * <p>比「数变大就红」更严一档：**按 key 比**。只盯数量的话，
 * 判定掉一条、又新加一列带取值注释的表，数字不变而新的那条溜进来了。
 */
export function ratchet(un = uncovered()) {
  const path = join(ROOT, RATCHET_FILE);
  const base = new Set(
    (existsSync(path) ? readFileSync(path, "utf8") : "")
      .split("\n")
      .map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#")),
  );
  const now = new Set(un.map((u) => u.key));
  return {
    added: un.filter((u) => !base.has(u.key)),
    fixed: [...base].filter((k) => !now.has(k)).sort(),
  };
}

export function audit() {
  const problems = [];
  const skipped = [];

  for (const f of FIELDS) {
    let backend, err;
    if (f.backend.javaConst) backend = javaConstValues(f.backend.javaConst, f.backend.only);
    else if (f.backend.ddl) {
      const r = ddlValues(...f.backend.ddl);
      if (r.error) err = r.error;
      else backend = f.backend.only ? r.values.filter((v) => f.backend.only.includes(v)) : r.values;
    } else backend = f.backend.literal;

    if (err || !backend?.length) {
      problems.push({ concept: f.concept, field: f.field, fatal: err || "后端取值域为空" });
      continue;
    }

    for (const c of f.clients) {
      const label = c.file.startsWith("ops-web") ? "ops-web" : "shared";
      const key = `${label}:${c.type || c.const}`;
      if (INTENTIONAL.has(key)) {
        skipped.push({ key, why: INTENTIONAL.get(key) });
        continue;
      }
      const path = join(ROOT, c.file);
      if (!existsSync(path)) continue;
      // 目录（shared 的 types/）整份读进来；文件照旧
      const src = statSync(path).isDirectory()
        ? readdirSync(path).filter((f) => f.endsWith(".ts"))
          .map((f) => readFileSync(join(path, f), "utf8")).join("\n")
        : readFileSync(path, "utf8");
      const declared = c.type ? unionValues(src, c.type) : constValues(src, c.const);
      if (!declared) {
        problems.push({
          concept: f.concept, field: f.field, client: key,
          fatal: `端上找不到 ${c.type || c.const} 的声明（改名了？还是内联成字面量了？）`,
        });
        continue;
      }
      const planned = c.plannedValues ?? (c.planned ? plannedValues(src, c.planned, c.const) : []);
      const b = new Set(backend);
      const d = new Set(declared);
      const clientOnly = declared.filter((v) => !b.has(v) && !planned.includes(v));
      const backendOnly = backend.filter((v) => !d.has(v));
      if (clientOnly.length || backendOnly.length) {
        problems.push({
          concept: f.concept, field: f.field, client: key,
          clientOnly, backendOnly,
          planned: planned.filter((v) => !b.has(v)),
        });
      }
    }
  }
  return { problems, skipped };
}

// ─────────────────────────────────────────────────────────────────────────────

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { problems, skipped } = audit();

  console.log("枚举对账 · 按字段比对取值域\n");
  console.log(`已登记 ${FIELDS.length} 个 wire 字段、显式驳回 ${DISMISSED.length} 列。\n`);

  const un = uncovered();
  const { added, fixed } = ratchet(un);
  console.log(`代码侧可见面 ${surface().size} 列，其中 ${un.length} 列还没有人判定过。`);
  console.log("（这不是覆盖率的分母 —— 分母应当是需求端定义的取值域，见 TDD §2.2。");
  console.log(`  这里只是让「已登记 ${FIELDS.length} 个」旁边有一份点得出名字的清单。）\n`);
  if (added.length) {
    console.log(`❌ 新出现 ${added.length} 列未判定的取值域（不在 ${RATCHET_FILE} 里）：`);
    for (const u of added) console.log(`   ${u.key}  ${u.values.join("/")}`);
    console.log("   → 登记进 FIELDS，或写进 DISMISSED 并给理由；两者都不做就把它加进基线并说明为什么\n");
  }
  if (fixed.length) {
    console.log(`🎉 这 ${fixed.length} 列已经判定完了，从 ${RATCHET_FILE} 里删掉（清单只准变短）：`);
    for (const k of fixed) console.log(`   ${k}`);
    console.log("");
  }

  if (!problems.length) {
    console.log("✅ 每个字段两侧的取值域完全一致\n");
  } else {
    for (const p of problems) {
      console.log(`── ${p.concept}  (${p.field})`);
      if (p.client) console.log(`   端：${p.client}`);
      if (p.fatal) {
        console.log(`   ❌ ${p.fatal}`);
      } else {
        if (p.clientOnly.length) {
          console.log(`   端上有、后端没有：${p.clientOnly.join(", ")}`);
          console.log(`     → 端上按它筛，筛出来的必然是空列表（不报错）`);
        }
        if (p.backendOnly.length) {
          console.log(`   后端有、端上没有：${p.backendOnly.join(", ")}`);
          console.log(`     → 后端下发时端上落进兜底分支，或把 i18n 键原样显示给用户`);
        }
        if (p.planned?.length) console.log(`   （已知待实现，不计入：${p.planned.join(", ")}）`);
      }
      console.log("");
    }
  }

  if (skipped.length) {
    console.log("── 显式豁免（差异是有意的）──");
    for (const s of skipped) console.log(`   ${s.key}\n     ${s.why}\n`);
  }

  console.log(
    "两侧都要看：端上多出来的值 → 筛不出东西；后端多出来的值 → 显示成键名或兜底。\n" +
      "同物异名（两边都有词但不是同一个词）只有按字段比对才抓得到 —— 这正是本工具存在的理由。",
  );
  if (problems.length || added.length) process.exitCode = 1;
}
