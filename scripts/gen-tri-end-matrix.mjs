#!/usr/bin/env node
/**
 * 三端功能点矩阵 —— 把同一个业务域在 C / B / 平台 三端的功能点并排摆出来。
 *
 * **与另两个生成器的分工**（三者用同一套后端扫描口径，数字必须能对上）：
 *   · gen-api-index      每个接口一行，给人逐条审
 *   · gen-delivery-status 每端的覆盖率，看进度
 *   · 本脚本              按业务域横向并排，看**一端有、另一端没有**
 *
 * 为什么要横向：前两份都可能全绿而流程照样断。用户能提工单、平台没有回复入口，
 * 在「端点实现率」上看不出来 —— 它要求把三端摆在一起，看某一列是不是空的。
 *
 * 用法：node scripts/gen-tri-end-matrix.mjs
 */
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/technical/reference/三端功能点矩阵.md");

/** 折叠路径参数：比的是形状不是参数名。与另两个生成器同一口径 */
const norm = (p) => p.replace(/[:{](\w+)\}?/g, "{id}").replace(/\/$/, "") || "/";

/**
 * 资源段 → 业务域。
 *
 * 这张表是**手工维护**的，因为三端对同一件事的命名本来就不统一：
 * C/B 端用单数（`after-sale`），平台端用复数（`after-sales`）；
 * 「资金」在 B 端叫 `settle`，平台端拆成了 `finance` / `settlements` / `split-records` 五个。
 * 不映射的话，同一个域会散成七八行，而「哪一列是空的」正是这份矩阵唯一要回答的问题。
 */
const DOMAIN = {
  "after-sale": "售后", "after-sales": "售后",
  auth: "认证与登录", user: "用户", customers: "用户",
  cart: "购物车与卡包", card: "购物车与卡包",
  community: "社区", communities: "社区",
  pickup: "自提点", pickups: "自提点",
  merchant: "商家主体", merchants: "商家主体",
  store: "门店", stores: "门店",
  goods: "商品", skus: "商品", "spec-templates": "商品",
  category: "类目", categories: "类目",
  order: "订单", orders: "订单",
  delivery: "履约与配送", "freight-templates": "履约与配送",
  shipments: "履约与配送", fulfillment: "履约与配送",
  review: "评价", reviews: "评价",
  "review-appeals": "评价", "review-score-config": "评价",
  coupon: "营销与优惠券", coupons: "营销与优惠券", "coupon-issues": "营销与优惠券",
  campaign: "营销与优惠券", campaigns: "营销与优惠券", marketing: "营销与优惠券",
  "group-buy": "拼团", "group-request": "拼团", groups: "拼团",
  points: "积分",
  settle: "资金与结算", settlements: "资金与结算", finance: "资金与结算",
  payments: "资金与结算", "split-records": "资金与结算",
  "refund-split-backs": "资金与结算", "fee-rule": "资金与结算",
  quote: "报价", quotes: "报价",
  message: "消息通知", "msg-templates": "消息通知",
  "notify-quota": "消息通知", "push-tasks": "消息通知",
  tickets: "客服工单", faqs: "客服工单",
  staff: "员工与角色", staffs: "员工与角色", roles: "员工与角色",
  dashboard: "看板",
  industries: "平台配置", "feature-flags": "平台配置", "audit-logs": "平台配置",
  "risk-events": "风控", "risk-rules": "风控", blacklists: "风控",
  contents: "内容运营", "content-slots": "内容运营", appearance: "内容运营",
  materials: "内容运营", "rule-texts": "内容运营",
  "fission-campaigns": "增长", "attribution-rule": "增长",
  "attribution-traces": "增长", demands: "增长", markets: "增长",
  upload: "素材",
  "master-data": "平台配置", config: "平台配置", "audit-log": "平台配置",
  attribution: "增长", context: "认证与登录", help: "客服工单", search: "商品",
  ticket: "客服工单",
};

const CLIENTS = [
  { key: "mp", label: "C 端", spec: "openapi.yaml" },
  { key: "biz", label: "B 端", spec: "openapi-b.yaml" },
  { key: "ops", label: "平台端", spec: "openapi-ops.yaml" },
];

/** 只解析 paths 段：产物是自家生成器出的，形状可控，不必引 YAML 库 */
function readSpec(file) {
  const out = [];
  if (!existsSync(file)) return out;
  let path = null;
  let op = null;
  let inPaths = false;
  for (const line of readFileSync(file, "utf8").split("\n")) {
    if (/^paths:\s*$/.test(line)) { inPaths = true; continue; }
    if (!inPaths) continue;
    if (/^\S/.test(line)) break;
    const pm = line.match(/^ {2}"?(\/[^":]*)"?:\s*$/);
    if (pm) { path = pm[1]; op = null; continue; }
    const mm = line.match(/^ {4}(get|post|put|delete):\s*$/);
    if (mm && path) { op = { path, method: mm[1].toUpperCase(), summary: "" }; out.push(op); continue; }
    const s = op && line.match(/^ {6}summary:\s*"(.*)"\s*$/);
    if (s) op.summary = s[1];
  }
  return out;
}

/** 后端实现集合。目录清单与 gen-delivery-status.mjs 一致 —— 只扫 portal 会少算一大半 */
function backendPaths() {
  const dirs = ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]
    .map((m) => join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"))
    .filter(existsSync);
  const out = new Set();
  const walk = (d) => {
    for (const e of readdirSync(d)) {
      const p = join(d, e);
      if (statSync(p).isDirectory()) walk(p);
      else if (e.endsWith("Controller.java")) {
        const src = readFileSync(p, "utf8");
        const base = src.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? "";
        for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
          const suffix = m[2];
          out.add(norm(suffix.startsWith("/") ? base + suffix : base || suffix));
        }
      }
    }
  };
  dirs.forEach(walk);
  return out;
}

const impl = backendPaths();
/** 域 → 端 → 功能点[] */
const matrix = new Map();
const put = (domain, client, row) => {
  if (!matrix.has(domain)) matrix.set(domain, new Map(CLIENTS.map((x) => [x.key, []])));
  matrix.get(domain).get(client).push(row);
};
const domainOf = (p) => DOMAIN[p.split("/")[2] ?? ""] ?? `未归类：${p.split("/")[2] ?? ""}`;

const declared = new Set();
for (const c of CLIENTS) {
  for (const op of readSpec(join(ROOT, "docs/api", c.spec))) {
    declared.add(norm(op.path));
    put(domainOf(op.path), c.key, { ...op, done: impl.has(norm(op.path)), declared: true });
  }
}

/*
 * 后端有、契约没声明的端点也要收进来。
 * 不收的话矩阵会**低估后端**：`/biz/quote` 明明实现了，B 端契约里没有它，
 * 于是「报价」域的 B 端列显示为空 —— 读者会以为商家侧没做报价，而它是有的。
 * 空单元格是这份矩阵唯一的结论来源，所以任何让单元格假空的情况都必须堵掉。
 */
for (const p of impl) {
  if (declared.has(p)) continue;
  const client = CLIENTS.find((c) => p.startsWith(`/${c.key}/`));
  if (!client) continue;   // /callback、/common 不属于任何一端
  put(domainOf(p), client.key, { path: p, method: "*", summary: "（契约未声明）", done: true, declared: false });
}

const count = (rows) => ({ n: rows.length, done: rows.filter((r) => r.done).length });
/** 闭环判定：某端声明了却一条没实现 = 断；有实现但不全 = 有缺口 */
function verdict(m) {
  const cs = CLIENTS.map((c) => count(m.get(c.key)));
  if (cs.some((c) => c.n > 0 && c.done === 0)) return "🔴";
  if (cs.some((c) => c.done < c.n)) return "🟡";
  return "✅";
}

const domains = [...matrix.entries()].sort((a, b) => {
  const w = { "🔴": 0, "🟡": 1, "✅": 2 };
  return w[verdict(a[1])] - w[verdict(b[1])] || a[0].localeCompare(b[0], "zh");
});

let md = `# 三端功能点矩阵

> 由 \`node scripts/gen-tri-end-matrix.mjs\` 生成，**请勿手改**。
> 功能点名取自三份 OpenAPI 契约的 \`summary\`；「后端」列是扫描 \`@*Mapping\` 得到的实现状态。
>
> 这份看的是**横向**：同一个业务域，三端各有哪些功能点、哪一列是空的。
> 纵向（端点实现率）见[三端全栈对齐清单](../archive/三端全栈对齐清单.md)；
> 断裂的成因与优先级见[三端闭环对齐清单](../archive/三端闭环对齐清单.md)。
>
> 图例：✅ 三端各自声明的都已实现 · 🟡 有实现但不全 · 🔴 **某一端声明了却一条都没实现**
>
> 「（契约未声明）」= 后端实现了但三份 OpenAPI 里没有它。收进来是为了不让单元格**假空**——
> 空单元格是这份矩阵唯一的结论来源，假空比不生成更糟。

---

## 一、总表

| 业务域 | C 端 | B 端 | 平台端 | 闭环 |
|---|---|---|---|:---:|
`;
for (const [d, m] of domains) {
  const cell = (k) => {
    const { n, done } = count(m.get(k));
    return n === 0 ? "—" : `${done}/${n}`;
  };
  md += `| ${d} | ${cell("mp")} | ${cell("biz")} | ${cell("ops")} | ${verdict(m)} |\n`;
}
md += `
> 单元格是「后端已实现 / 契约已声明」。「—」表示该端没有这个域，**不一定是缺陷**——
> 比如「购物车」本来就只有 C 端有。要判断是不是缺陷，看[闭环清单](../archive/三端闭环对齐清单.md)。

---

## 二、逐域功能点

`;
for (const [d, m] of domains) {
  const total = CLIENTS.reduce((s, c) => s + count(m.get(c.key)).n, 0);
  md += `### ${verdict(m)} ${d}（${total} 个功能点）\n\n`;
  md += `| 端 | 方法 | 路径 | 功能点 | 后端 |\n|---|---|---|---|:---:|\n`;
  for (const c of CLIENTS) {
    const rows = m.get(c.key);
    if (!rows.length) continue;
    rows.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
    for (const r of rows) {
      md += `| ${c.label} | ${r.method} | \`${r.path}\` | ${r.summary || "—"} | ${r.done ? "✅" : "⬜"} |\n`;
    }
  }
  md += `\n`;
}
writeFileSync(OUT, md);
const t = domains.reduce((s, [, m]) => s + CLIENTS.reduce((x, c) => x + count(m.get(c.key)).n, 0), 0);
const broken = domains.filter(([, m]) => verdict(m) === "🔴").length;
console.log(`✅ ${OUT}`);
console.log(`   ${domains.length} 个域 · ${t} 个功能点 · 🔴 ${broken} 个域有整端未落地`);
