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
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(fileURLToPath(new URL(".", import.meta.url)), "..");
const SHARED_TYPES = "packages/shared/src/types/index.ts";
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
      only: ["STORE_PICKUP", "NEIGHBOR_PICKUP", "MERCHANT_DELIVERY", "EXPRESS"],
    },
    clients: [
      { file: SHARED_CONST, const: "FULFILLMENT", planned: "PLANNED_FULFILLMENTS" },
      // 漏登过一次：ops-web 因此多了一个后端没有的 SERVICE，而对账一路绿灯
      { file: "ops-web/lib/types/order.ts", type: "FulfillmentType", plannedValues: ["STORE_VERIFY"] },
    ],
  },
  {
    concept: "五品类（商品形态）",
    field: "prd_goods.type",
    backend: { ddl: ["prd_goods", "type"] },
    clients: [{ file: SHARED_CONST, const: "CATEGORY_TYPE" }],
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
];

/**
 * 显式不比对的项。每条都要写清楚**为什么这个差异是对的**，
 * 而不是「暂时先放过」—— 后者会让这张表变成垃圾场（豁免名单静音过一次真 bug，
 * 见 docs/technical/枚举统一方案.md §0）。
 */
const INTENTIONAL = new Map([
  [
    "ops-web:CampaignType",
    "**重名但不是同一个东西**：ops-web 的 Campaign 是平台投放场次（带 position，" +
      "秒杀场按投放位置分组做重叠校验），由运营建；mkt_campaign 是店铺级活动" +
      "（entity_no NOT NULL，不跨店），由商家建。后端确无对应表。" +
      "按 mkt_campaign 那套改名等于把两个概念合并成一个 —— 见 " +
      "ops-web/lib/types/marketing.ts 的注释与 docs/technical/营销枚举对账报告.md §1③。",
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

/** 取建表语句列注释里的取值域：`COMMENT 'A/B/C：说明'` */
function ddlValues(table, column) {
  const dir = join(ROOT, "backend/shop-app/src/main/resources/db/migration");
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".sql")).sort()) {
    const src = readFileSync(join(dir, f), "utf8");
    // 建表语句以 `) ENGINE=...;` 收尾，不是裸的 `);`
    const block = src.match(
      new RegExp(`CREATE TABLE IF NOT EXISTS ${table}\\s*\\n\\(([\\s\\S]*?)\\n\\)\\s*ENGINE`),
    );
    if (!block) continue;
    for (const line of block[1].split("\n")) {
      if (!new RegExp(`^\\s+${column}\\s`).test(line)) continue;
      const c = line.match(/COMMENT\s+'([^']*)'/);
      if (!c) return { error: `${table}.${column} 建表语句没有列注释，取值域无处可查` };
      /*
       * 列注释里取值域有两种写法，都要认：
       *   `A/B/C：说明`          —— 取值挤在开头，说明在冒号后
       *   `A=说明 / B=说明`      —— 每个取值自带说明
       * 后一种如果只切第一段，就只剩 A 了 —— 那会把 B、C 误报成「端上编的词」。
       */
      const eq = [...c[1].matchAll(/\b([A-Z][A-Z0-9_]+)\s*=/g)].map((x) => x[1]);
      const vals = eq.length
        ? eq
        : [...c[1].split(/[：:，,（(]/)[0].matchAll(/([A-Z][A-Z0-9_]{1,})/g)].map((x) => x[1]);
      if (!vals.length) return { error: `${table}.${column} 的注释里没有取值域：${c[1]}` };
      return { values: vals };
    }
  }
  return { error: `找不到 ${table}.${column} 的建表语句` };
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
      const src = readFileSync(path, "utf8");
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
  console.log(`已登记 ${FIELDS.length} 个 wire 字段。\n`);

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
  if (problems.length) process.exitCode = 1;
}
