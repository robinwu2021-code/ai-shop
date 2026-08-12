#!/usr/bin/env node
/**
 * ER 图生成器 —— 从 Flyway 迁移直接生成，**不手画**。
 *
 * 为什么必须生成：手画的 ER 图会漂移，而且漂移看不出来。
 * 上一版手写的积分域 ER 图里有三处字段名与实际不符（`consumer_merchant_no` 实为
 * `acceptor_merchant_no`、状态值多写了两个、账期单状态少写一个），
 * 是逐列比对 V17 才发现的 —— 而在那之前它看起来完全正常。
 *
 * 为什么按域拆图：一张图放 55 张表是团乱麻，谁也看不懂。
 * 按表名前缀分域，每张图只画本域的表，跨域引用单独列出来。
 *
 * 用法：npm run gen:erd
 * 查看：docs/technical/reference/数据库-ER图.html（侧栏导航 + 内联 SVG），或 .md 走编辑器预览
 */
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { domainOverview, tableGraph } from "./lib/svg-erd.mjs";
import { readSchema } from "./lib/ddl.mjs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/technical/reference/数据库-ER图.md");
// 图放在 technical/diagrams/，而文档在 technical/reference/ —— md 里因此是 ../diagrams/。
// 写死 ./diagrams/ 的话生成出来就是断链，而 md 预览里图只是不显示，不报错。
const DIAG = join(ROOT, "docs/technical/diagrams");
const OUT_HTML = join(ROOT, "docs/technical/reference/数据库-ER图.html");

/**
 * 表名前缀 → 域。顺序即输出顺序。
 *
 * ⚠️ **这张表决定了什么会被画出来，也决定了什么会消失**：`domainOf` 认不出的前缀
 * 既不进域列表、也不进任何一张图，而总表数是单独数出来的 ——
 * 于是「全库 87 张表」下面那张域表加起来只有 68，**不报错，也没人会去加**。
 *
 * 2026-08-12 补了 `mch` 与 `cnt` 两个域（19 张表）。`mch_*` 里包括
 * `mch_store_role` —— B 端整套角色权限就挂在它上面，而它此前在 ER 图里根本不存在。
 * 加新域的表时，**这里要跟着加一行**。
 */
const DOMAINS = [
  ["usr", "消费者账号"],
  ["mch", "商家主体与门店"],
  ["cmt", "社区与自提点"],
  ["prd", "商品与类目"],
  ["trd", "购物车"],
  ["ord", "交易"],
  ["ful", "履约"],
  ["mkt", "营销与团购"],
  ["pts", "积分"],
  ["stl", "结算"],
  ["rvw", "评价"],
  ["msg", "消息与客服"],
  ["cnt", "内容"],
  ["sys", "系统"],
];

/**
 * 业务键归属：这个 `xxx_no` 是谁的主键。
 *
 * **必须显式登记，不能从表名推断** —— 真实的表名与主键列名经常对不上：
 * `stl_bill` 的主键叫 `settle_no`、`pts_user_ledger` 的叫 `ledger_no`。
 * 与 packages/shared/tests/schema-lineage.test.ts 的同名表保持一致（有断言校验）。
 */
export const KEY_OWNERS = {
  entity_no: "mch_entity",
  // 门店与商家账号。store_no 与 entity_no 是两级，别按名字互相 join
  store_no: "mch_store",
  mch_account_no: "mch_account",
  pay_merchant_no: "mch_payment_merchant",
  user_no: "usr_account",
  order_no: "ord_order",
  sub_order_no: "ord_sub_order",
  community_no: "cmt_community",
  pickup_no: "cmt_pickup_point",
  goods_no: "prd_goods",
  sku_no: "prd_sku",
  category_no: "prd_category",
  coupon_no: "mkt_coupon",
  quote_no: "mkt_quote",
  group_no: "mkt_group_buy",
  review_no: "rvw_review",
  staff_no: "sys_ops_staff",
  settle_no: "stl_bill",
  after_sale_no: "ord_after_sale",
  payment_no: "stl_payment",
  // 商家进账挂用户的 USE 流水：一次使用一条进账。积分发出后与发放方脱钩（V28）
  use_ledger_no: "pts_user_ledger",
  inviter_no: "usr_account",
  issuer_merchant_no: "mch_entity",
  // 保证金账户/流水按商家挂；命名欠账见 schema-lineage.test.ts 里的说明
  merchant_no: "mch_entity",
};

/**
 * 专题图：一个业务域**横跨多个表名前缀**时，按前缀分的图看不全它。
 *
 * 积分就是这样 —— `pts_*` 两张表 + 结算侧三张，
 * 加上 `ord_sub_order`（资金账与积分账的勾稽点）才是完整的一张图。
 * 这里只列表名，关系仍从 schema 里算，所以**不会漂移**。
 */
const TOPICS = [
  {
    id: "money",
    label: "资金全链路",
    tables: [
      "ord_order",
      "ord_sub_order",
      "ord_after_sale",
      "stl_payment",
      "stl_bill",
      "stl_split_log",
      "pts_user_ledger",
      "stl_points_pool",
    ],
  },
  {
    id: "points",
    label: "积分域",
    tables: [
      "ord_sub_order",
      "pts_user_account",
      "pts_user_ledger",
      "stl_bill",
      "stl_points_pool",
    ],
  },
];

/**
 * 同名不同义：**不可按名字连线**，连了就是错的。
 *
 * 键是列名，值是理由 —— 文档里那张表由它渲染，不再手写一份。
 * 手写的那份在 `pts_merchant_ledger` 被删（V34）之后还挂着 `ledger_no` 一行，
 * 而守卫只查集合不查文档，漏了两天没人发现。
 */
const NAME_COLLISIONS_WHY = {
  request_no: "`mkt_request` 是求团需求单号；`stl_split_log` 是分账幂等号",
  express_no: "`ord_sub_order` 是发货单号；`ord_after_sale` 是退货单号，**方向相反**",
  operator_no: "各表各自记录操作人，不是外键",
};
const NAME_COLLISIONS = new Set(Object.keys(NAME_COLLISIONS_WHY));

/** 每张表都有，画进图里只会挤满屏幕 */
const AUDIT = new Set([
  "id", "tenant_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted",
]);

// ---------------------------------------------------------------- 关系
/** 表 A 的某列是表 B 的主键 → A 引用 B */
function relations(tables) {
  const out = [];
  for (const [table, def] of tables) {
    for (const c of def.cols) {
      if (AUDIT.has(c.name) || NAME_COLLISIONS.has(c.name)) continue;
      const owner = KEY_OWNERS[c.name];
      if (!owner || owner === table || !tables.has(owner)) continue;
      out.push({ from: table, to: owner, col: c.name });
    }
  }
  return out;
}

const domainOf = (t) => t.split("_")[0];

// ---------------------------------------------------------------- 渲染
const tables = readSchema(ROOT);
if (!tables.size) {
  console.error("✗ 一张表都没解析到 —— 建表写法变了？");
  process.exit(1);
}
const rels = relations(tables);

mkdirSync(DIAG, { recursive: true });

// ── 域级总览 SVG
const domainList = DOMAINS.map(([prefix, label]) => ({
  prefix,
  label,
  count: [...tables.keys()].filter((t) => domainOf(t) === prefix).length,
})).filter((d) => d.count);

const domainEdges = {};
for (const r of rels) {
  const a = domainOf(r.from), b = domainOf(r.to);
  if (a !== b) domainEdges[`${a}>${b}`] = (domainEdges[`${a}>${b}`] ?? 0) + 1;
}
writeFileSync(join(DIAG, "db-overview.svg"), domainOverview(domainList, domainEdges));

// ── 各域表关系 SVG
for (const d of domainList) {
  const own = [...tables.keys()]
    .filter((t) => domainOf(t) === d.prefix)
    .map((name) => ({ name, comment: tables.get(name).comment }));
  const inner = rels.filter((r) => domainOf(r.from) === d.prefix && domainOf(r.to) === d.prefix);
  writeFileSync(join(DIAG, `db-${d.prefix}.svg`), tableGraph(d.label, own, inner));
}

// ── 专题图
for (const t of TOPICS) {
  const missing = t.tables.filter((n) => !tables.has(n));
  if (missing.length) {
    console.error(`✗ 专题「${t.label}」引用了不存在的表：${missing.join("、")}`);
    process.exit(1);
  }
  const own = t.tables.map((name) => ({ name, comment: tables.get(name).comment }));
  const inner = rels.filter((r) => t.tables.includes(r.from) && t.tables.includes(r.to));
  writeFileSync(join(DIAG, `topic-${t.id}.svg`), tableGraph(t.label, own, inner));
}

// 自引用（usr_x → usr_y）不算「被别的域引用」，否则锚点会虚高一位
const inboundDomains = (prefix) =>
  new Set(
    Object.keys(domainEdges)
      .map((k) => k.split(">"))
      .filter(([from, to]) => to === prefix && from !== prefix)
      .map(([from]) => from),
  ).size;
// 锚点不写死：以后哪个域成了新的引用中心，文档自己会跟上
const anchor = domainList.reduce((a, b) => (inboundDomains(b.prefix) > inboundDomains(a.prefix) ? b : a));

// ── 渐进式 Markdown（骨架见 docs/文档规范.md §三）
const md = [
  "# 数据库 ER 图",
  "",
  "> 由 `npm run gen:erd` 从 Flyway 迁移生成，**请勿手改**。",
  "> 图为 SVG，任何环境都能打开；改表后重跑即可，不会漂移。",
  "",
  "## 一、总览",
  "",
  `全库 **${tables.size}** 张表、**${rels.length}** 条引用关系，分 **${domainList.length}** 个域。`,
  "按「被引用次数」分三条带 —— **不是有向无环图**：域之间存在环",
  "（`cmt → mkt → usr → cmt`），强行分层会画错。",
  "",
  "![数据库域总览](../diagrams/db-overview.svg)",
  "",
  "| 域 | 前缀 | 表数 | 被几个域引用 |",
  "|---|---|---:|---:|",
  ...domainList
    .map((d) => {
      return `| ${d.label} | \`${d.prefix}_*\` | ${d.count} | ${inboundDomains(d.prefix)} |`;
    }),
  "",
  `> \`${anchor.prefix}\` 被 ${inboundDomains(anchor.prefix)} 个域引用 —— 它是全库的锚点。改它的主键或语义，影响面是全局的。`,
  "",
  "## 二、分域",
  "",
];

for (const d of domainList) {
  const own = [...tables.keys()].filter((t) => domainOf(t) === d.prefix);
  const out = [
    ...new Set(
      rels
        .filter((r) => own.includes(r.from) && !own.includes(r.to))
        .map((r) => `\`${r.from}.${r.col}\` → \`${r.to}\``),
    ),
  ];
  md.push(
    `### ${d.label} \`${d.prefix}_*\`（${d.count} 张）`,
    "",
    `![${d.label}表关系](../diagrams/db-${d.prefix}.svg)`,
    "",
    "| 表 | 说明 |",
    "|---|---|",
    ...own.map((t) => `| \`${t}\` | ${tables.get(t).comment || "—"} |`),
    "",
  );
  if (out.length) md.push(`**跨域引用**：${out.join("、")}`, "");
}

md.push(
  "## 三、逐表详情",
  "",
  "见网页版 [数据库-ER图.html](./数据库-ER图.html) —— 54 张表的字段、类型、索引、关联。",
  "Markdown 里铺开会有近两千行，那不是给人读的。",
  "",
  "## 四、不可按名字连线的列",
  "",
  "以下列名出现在多张表里但**语义不同**，图中刻意不连：",
  "",
  "| 列 | 为什么 |",
  "|---|---|",
  ...Object.entries(NAME_COLLISIONS_WHY).map(([k, why]) => `| \`${k}\` | ${why} |`),
  "",
);

writeFileSync(OUT, md.join("\n"));

// ---------------------------------------------------------------- HTML
//
// 同一份数据出两个产物：
//   .md   给 GitHub 与编辑器预览（原生渲染 mermaid，随仓库版本化）
//   .html 给浏览器 —— 55 张表 12 个域，侧栏导航比上下滚一份长文档好用得多
//
// 两份都生成，是因为它们的使用场合不同，而不是留了个备份。
const esc = (t) => String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/**
 * 列注释里写 `**强调**` 是本项目 DDL 的既有风格（很多迁移都这么写）。
 * 直接塞进 HTML 会显示成字面的星号 —— 渲染成 <strong>，别让人以为是乱码。
 */
const escMd = (t) => esc(t).replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");

const sections = [];
const navItems = [];
for (const [prefix, label] of DOMAINS) {
  const own = [...tables.keys()].filter((t) => domainOf(t) === prefix);
  if (!own.length) continue;
  navItems.push({ id: prefix, label, count: own.length });

  const inner = rels.filter((r) => own.includes(r.from) && own.includes(r.to));
  const outward = rels.filter((r) => own.includes(r.from) && !own.includes(r.to));
  const inward = rels.filter((r) => !own.includes(r.from) && own.includes(r.to));

  // 关系图**只画框和线，不画字段**。
  // 把列全塞进 erDiagram 的实体块里，正是「图看不清」的根因：
  // 一个域十来张表、每张十几列，渲染出来的框比屏幕还高。
  // 字段留给下面的详情卡，图只回答一个问题：谁指向谁。
  // 内联 SVG，不用 mermaid：写错一个字符整张图不显示且不报错
  const svg = readFileSync(join(DIAG, `db-${prefix}.svg`), "utf8");

  // 每张表一张详情卡
  const cards = own
    .map((t) => {
      const def = tables.get(t);
      const refsOut = rels.filter((r) => r.from === t);
      const refsIn = rels.filter((r) => r.to === t);
      const rows = def.cols
        .filter((c) => !AUDIT.has(c.name))
        .map((c) => {
          const badge = def.uniques.has(c.name)
            ? '<i class="k uk">UK</i>'
            : KEY_OWNERS[c.name] && KEY_OWNERS[c.name] !== t
              ? '<i class="k fk">FK</i>'
              : "";
          return `<tr><td class="cn"><code>${c.name}</code>${badge}</td>` +
            `<td class="ct">${c.type}</td>` +
            `<td class="cnl">${c.nullable ? "可空" : "必填"}</td>` +
            `<td>${escMd(c.comment || "—")}</td></tr>`;
        })
        .join("");
      const relLines = [
        ...refsOut.map((r) => `<code>${r.col}</code> → <code>${r.to}</code>`),
        ...refsIn.map((r) => `<code>${r.from}</code> 经 <code>${r.col}</code> 指向本表`),
      ];
      return `<article class="card" id="t-${t}">
<h3><code>${t}</code><span class="tdesc">${escMd(def.comment || "")}</span></h3>
<table class="cols"><thead><tr><th>列</th><th>类型</th><th>空</th><th>说明</th></tr></thead>
<tbody>${rows}</tbody></table>
${relLines.length ? `<p class="rel"><span class="rl">关联</span>${relLines.join("<br>")}</p>` : ""}
${def.indexes.length ? `<p class="rel"><span class="rl">索引</span>${def.indexes.map((i) => `<code>(${esc(i)})</code>`).join(" ")}</p>` : ""}
</article>`;
    })
    .join("\n");

  sections.push(`<section id="${prefix}">
<h2>${esc(label)} <span class="tag">${prefix}_*</span> <span class="count">${own.length} 张表</span></h2>
<div class="canvas">${svg}</div>
${inward.length || outward.length ? `<p class="xref"><span class="xlabel">跨域</span>${[
    ...new Set([
      ...outward.map((r) => `${r.from}.${r.col} → ${r.to}`),
      ...inward.map((r) => `${r.from}.${r.col} → ${r.to}`),
    ]),
  ].map((x) => `<code>${esc(x)}</code>`).join("")}</p>` : ""}
${cards}
</section>`);
}

const html = `<title>数据库 ER 图 · 按域</title>
<style>
:root{--paper:#FBFCFD;--ink:#161A21;--muted:#667080;--rule:#E1E6EC;--surface:#FFF;
--accent:#2F6F7E;--accent-soft:#E8F1F3;--warn:#9C5B33;--warn-soft:#F7EFE9;--canvas:#FFF}
@media (prefers-color-scheme:dark){:root{--paper:#10131A;--ink:#E4E9F0;--muted:#8B94A3;
--rule:#232935;--surface:#171B24;--accent:#6FB3C0;--accent-soft:#1A2A2F;--warn:#C88B5E;--warn-soft:#2A211A}}
:root[data-theme="dark"]{--paper:#10131A;--ink:#E4E9F0;--muted:#8B94A3;--rule:#232935;
--surface:#171B24;--accent:#6FB3C0;--accent-soft:#1A2A2F;--warn:#C88B5E;--warn-soft:#2A211A}
:root[data-theme="light"]{--paper:#FBFCFD;--ink:#161A21;--muted:#667080;--rule:#E1E6EC;
--surface:#FFF;--accent:#2F6F7E;--accent-soft:#E8F1F3;--warn:#9C5B33;--warn-soft:#F7EFE9}
*{box-sizing:border-box}
body{margin:0;background:var(--paper);color:var(--ink);
font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;line-height:1.65}
code,.mono{font-family:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace}
.wrap{display:grid;grid-template-columns:212px minmax(0,1fr);gap:40px;
max-width:1180px;margin:0 auto;padding:40px 28px 96px}
nav{position:sticky;top:28px;align-self:start;max-height:calc(100vh - 56px);overflow-y:auto}
.eyebrow{font-family:ui-monospace,monospace;font-size:11px;letter-spacing:.14em;
text-transform:uppercase;color:var(--muted);margin:0 0 14px}
nav a{display:flex;justify-content:space-between;gap:8px;padding:7px 10px;border-radius:5px;
color:var(--muted);text-decoration:none;font-size:13.5px;border-left:2px solid transparent}
nav a:hover{background:var(--accent-soft);color:var(--ink)}
nav a.on{background:var(--accent-soft);color:var(--accent);border-left-color:var(--accent);font-weight:500}
nav a b{font-family:ui-monospace,monospace;font-size:11.5px;font-weight:400;
font-variant-numeric:tabular-nums;opacity:.7}
h1{font-size:27px;font-weight:600;letter-spacing:-.02em;margin:0 0 6px;text-wrap:balance}
.lede{color:var(--muted);font-size:14.5px;margin:0 0 8px;max-width:64ch}
.meta{font-family:ui-monospace,monospace;font-size:12px;color:var(--muted);
font-variant-numeric:tabular-nums;margin:0 0 34px}
h2{font-size:18px;font-weight:600;letter-spacing:-.01em;margin:0 0 14px;
display:flex;align-items:baseline;gap:9px;flex-wrap:wrap}
.tag{font-family:ui-monospace,monospace;font-size:12px;font-weight:400;color:var(--accent);
background:var(--accent-soft);padding:2px 7px;border-radius:4px}
.count{font-family:ui-monospace,monospace;font-size:12px;font-weight:400;color:var(--muted);
font-variant-numeric:tabular-nums;margin-left:auto}
section{padding-top:20px;margin-bottom:52px;scroll-margin-top:24px}
section+section{border-top:1px solid var(--rule)}
.canvas{background:var(--canvas);border:1px solid var(--rule);border-radius:8px;
padding:18px;overflow-x:auto;margin-bottom:16px}
.mermaid{margin:0}
table.legend{width:100%;border-collapse:collapse;font-size:13.5px;margin-bottom:14px}
table.legend th{text-align:left;font-weight:500;font-size:11px;letter-spacing:.1em;
text-transform:uppercase;color:var(--muted);padding:0 10px 7px;border-bottom:1px solid var(--rule)}
table.legend td{padding:8px 10px;border-bottom:1px solid var(--rule);vertical-align:top}
table.legend tr:last-child td{border-bottom:none}
table.legend code{font-size:12.5px;color:var(--accent);white-space:nowrap}
.xref{display:flex;flex-wrap:wrap;gap:6px;align-items:center;font-size:13px;margin:0}
.xlabel{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);margin-right:2px}
.xref code{background:var(--surface);border:1px solid var(--rule);padding:3px 8px;
border-radius:4px;font-size:12px}
.caveat{background:var(--warn-soft);border:1px solid var(--rule);border-left:3px solid var(--warn);
border-radius:6px;padding:18px 20px;margin-top:8px}
.caveat h2{margin-bottom:8px;color:var(--warn)}
.caveat p{font-size:14px;color:var(--muted);margin:0 0 14px;max-width:62ch}
.caveat table{width:100%;border-collapse:collapse;font-size:13.5px}
.caveat td{padding:7px 10px 7px 0;vertical-align:top;border-bottom:1px solid var(--rule)}
.caveat tr:last-child td{border-bottom:none}
.caveat code{color:var(--warn);white-space:nowrap}
.card{background:var(--surface);border:1px solid var(--rule);border-radius:9px;
padding:16px 18px;margin-bottom:14px;scroll-margin-top:20px}
.card h3{font-size:15px;font-weight:600;margin:0 0 12px;display:flex;
align-items:baseline;gap:10px;flex-wrap:wrap}
.card h3 code{font-family:ui-monospace,monospace;font-size:14px;color:var(--accent)}
.tdesc{font-size:12.5px;font-weight:400;color:var(--muted)}
table.cols{width:100%;border-collapse:collapse;font-size:13px}
table.cols th{text-align:left;font-weight:500;font-size:10.5px;letter-spacing:.09em;
text-transform:uppercase;color:var(--muted);padding:0 10px 6px;border-bottom:1px solid var(--rule)}
table.cols td{padding:6px 10px;border-bottom:1px solid var(--rule);vertical-align:top}
table.cols tr:last-child td{border-bottom:none}
td.cn code{font-size:12.5px;white-space:nowrap}
td.ct{font-family:ui-monospace,monospace;font-size:11.5px;color:var(--muted);white-space:nowrap}
td.cnl{font-size:11.5px;color:var(--muted);white-space:nowrap}
.k{font-style:normal;font-size:9.5px;font-weight:500;padding:1px 4px;border-radius:3px;
margin-left:6px;vertical-align:middle}
.k.uk{background:var(--accent-soft);color:var(--accent)}
.k.fk{background:var(--warn-soft);color:var(--warn)}
.rel{font-size:12.5px;color:var(--muted);margin:12px 0 0;line-height:1.9}
.rl{font-size:10.5px;letter-spacing:.09em;text-transform:uppercase;
color:var(--muted);margin-right:8px}
.rel code{background:var(--code-bg,var(--accent-soft));padding:1px 5px;border-radius:3px;font-size:11.5px}
nav details{margin:2px 0 0}
nav summary{cursor:pointer;list-style:none;padding:3px 10px 3px 22px;font-size:11.5px;color:var(--muted)}
nav summary::-webkit-details-marker{display:none}
nav details a{padding:3px 10px 3px 26px;font-size:11.5px;font-family:ui-monospace,monospace}
@media (max-width:860px){.wrap{grid-template-columns:1fr;gap:24px;padding:28px 18px 72px}
nav{position:static;max-height:none;display:flex;flex-wrap:wrap;gap:6px}
nav .eyebrow{width:100%;margin-bottom:6px}nav a{border-left:none;border:1px solid var(--rule)}}
</style>
<div class="wrap">
<nav>
<p class="eyebrow">领域</p>
${navItems
  .map((n) => {
    const ts = [...tables.keys()].filter((t) => domainOf(t) === n.id);
    return `<a href="#${n.id}"><span>${esc(n.label)}</span><b>${n.count}</b></a>
<details><summary>展开表名</summary>${ts.map((t) => `<a href="#t-${t}">${t}</a>`).join("")}</details>`;
  })
  .join("\n")}
</nav>
<main>
<h1>数据库 ER 图</h1>
<p class="lede">由 <span class="mono">npm run gen:erd</span> 从 Flyway 迁移生成。手画的 ER 图会漂移且看不出来 —— 上一版手写的积分域图有三处字段名与实际不符，是逐列比对迁移才发现的。</p>
<p class="meta">${tables.size} 张表 · ${rels.length} 条引用关系 · ${navItems.length} 个域 · 已省略审计列</p>
<div class="canvas">${readFileSync(join(DIAG, "db-overview.svg"), "utf8")}</div>
<p class="lede">按「被引用次数」分三条带 —— <b>不是有向无环图</b>：域之间存在环（<span class="mono">cmt → mkt → usr → cmt</span>），强行分层会画错。</p>
${sections.join("\n")}
<section id="caveat"><div class="caveat">
<h2>不可按名字连线的列</h2>
<p>以下列名出现在多张表里但语义不同，图中刻意不给它们画关系。按名字连就是错的。</p>
<table><tbody>
${Object.entries(NAME_COLLISIONS_WHY)
  .map(([k, why]) => `<tr><td><code>${k}</code></td><td>${escMd(why.replace(/`([^`]+)`/g, "<code>$1</code>"))}</td></tr>`)
  .join("\n")}
</tbody></table>
</div></section>
</main>
</div>
<script>
const links=[...document.querySelectorAll('nav a')];
const io=new IntersectionObserver((es)=>{es.forEach(e=>{if(!e.isIntersecting)return;
links.forEach(l=>l.classList.toggle('on',l.getAttribute('href')==='#'+e.target.id))})},
{rootMargin:'-80px 0px -70% 0px'});
document.querySelectorAll('section[id]').forEach(s=>io.observe(s));
</script>`;

writeFileSync(OUT_HTML, html);
console.log(`✅ ${OUT}`);
console.log(`✅ ${OUT_HTML}`);
console.log(`   ${tables.size} 张表 · ${rels.length} 条关系 · ${domainList.length} 个域 · ${domainList.length + 1 + TOPICS.length} 张 SVG`);
