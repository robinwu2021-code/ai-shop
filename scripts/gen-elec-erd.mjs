#!/usr/bin/env node
/**
 * 元器件库（`ai_shop_elec`）的 ER 图与表清单。
 *
 * **为什么不并进 gen-erd.mjs**：那份画的是 `ai_shop`，它的域列表、锚点判定、
 * 「被几个域引用」都是按那一个库算的。把另一个库的 13 张表混进去，
 * 全库张数、域总览、锚点三个数字当场全错 —— 而它们看起来仍然像对的。
 * 进销存（`ai_shop_inv`）没进那份，理由相同。
 *
 * **关系从哪来**：本库没有外键（与进销存同一条通则），引用靠业务键列名推断。
 * 归属表写在下面的 KEY_OWNERS 里 —— 只有 5 个键，手工维护得起，
 * 而且每一条都能一句话说清谁是主。
 *
 * 用法：
 *   node scripts/gen-elec-erd.mjs            # 生成 SVG 与 md
 *   node scripts/gen-elec-erd.mjs --check    # 只校验（给闸门用）
 */
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { tableGraph } from "./lib/svg-erd.mjs";
import { readSchema, ELEC_MIGRATION_DIR, AUDIT } from "./lib/ddl.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const DIAG = join(ROOT, "docs/technical/diagrams");
const SVG = join(DIAG, "db-elec.svg");
const OUT = join(ROOT, "docs/technical/reference/数据库-元器件.md");

/**
 * 业务键 → 它属于哪张表。**判据是「哪张表把它建成唯一键」**，与平台那份 KEY_OWNERS 同一条规矩。
 *
 * `account_ref` / `buyer_ref` 刻意不在这里：它们指向的是**另一个系统**的用户号
 * （主系统的 `usr_no`），本库里没有那张表 —— 画成一条指向空处的箭头只会让人以为漏了张表。
 * 「跨库引用只存外部键」这件事写在表注释与设计文档里，不靠 ER 图表达。
 */
const KEY_OWNERS = {
  mfr_code: "elc_manufacturer",
  part_no: "elc_part",
  supplier_no: "elc_supplier",
  batch_no: "elc_stock_batch",
  rfq_no: "elc_rfq",
};

/** 一句话说清每张表是干什么的。**没登记的表会让本脚本失败** —— 新表进来必须表态 */
const PURPOSE = {
  elc_manufacturer: "厂牌。种子写、代码只读",
  elc_mfr_alias: "厂牌别名：TI / Texas Instruments / 德州仪器 指同一家。**搜索命中率靠它**",
  elc_part: "料号。平台的资产，不属于任何供应商；第一步由上传长出来",
  elc_part_key: "料号分段键：在字母/数字交界处切开，让「F103C8」这种中段也能按前缀搜到",
  elc_part_market: "**买家面的库存投影**：数量与家数只存档位，没有供应商列",
  elc_supplier: "供应商主体。点一下就成为供应商，公司名等之后补",
  elc_supplier_member: "谁能代表这家供应商。account_ref = 主系统的用户号",
  elc_stock_batch: "一次上传：列映射、预演出来的四个数（新增/更新/下架/未变）",
  elc_stock_batch_row: "上传原样的每一行。只追加 —— 换列映射时按它重算，不用再传一次文件",
  elc_stock: "供应商库存行。**精确数量与批号只在这张表**，买家读不到",
  elc_rfq: "询价单 + 平台报价（第一步一单一个报价方：平台）",
  elc_rfq_line: "询价行 + 这一行的报价。quote_e6 为空 = 这一行没找到货",
  elc_search_daily: "搜索需求日聚合：搜了什么、几次没结果。**不记是谁搜的**",
};

const rels = [];
const tables = readSchema(ROOT, [ELEC_MIGRATION_DIR]);
if (!tables.size) {
  console.error("✗ 一张表都没解析到 —— 建表写法变了？");
  process.exit(1);
}
for (const [table, def] of tables) {
  for (const c of def.cols) {
    if (AUDIT.has(c.name)) continue;
    const owner = KEY_OWNERS[c.name];
    if (!owner || owner === table || !tables.has(owner)) continue;
    rels.push({ from: table, to: owner, col: c.name });
  }
}

const missing = [...tables.keys()].filter((t) => !PURPOSE[t]);
if (missing.length) {
  console.error(`✗ 这些表没在 PURPOSE 里表态：${missing.join("、")}\n`
    + "  一句话说清它是干什么的 —— 表清单的价值全在这一句，没有它就只是一份表名列表。");
  process.exit(1);
}

const list = [...tables.keys()].sort().map((name) => ({ name, cols: tables.get(name).cols }));
const svg = tableGraph("元器件", list, rels);

const md = [
  "# 数据库 · 元器件（ai_shop_elec）",
  "",
  "> 【自动生成，勿手改】`node scripts/gen-elec-erd.mjs`，源是 "
    + "`backend/elec/elec-core/src/main/resources/db/elec/V*.sql`。",
  "> 设计与取舍见 [TDD-元器件-独立服务与第一步](../TDD-元器件-独立服务与第一步.md)。",
  "",
  `**另一个库**，与 \`ai_shop\` 零共享表、零跨库 join：${tables.size} 张表、`
    + `${rels.length} 条引用关系，跑在独立进程 elec-svc 里。`,
  "搬走那天库原样带走 —— 这正是「第一刀切在数据」的意思。",
  "",
  "![元器件表关系](../diagrams/db-elec.svg)",
  "",
  "## 表",
  "",
  "| 表 | 列 | 做什么 |",
  "|---|---|---|",
  ...list.map((t) => `| \`${t.name}\` | ${t.cols.length} | ${PURPOSE[t.name]} |`),
  "",
  "## 引用关系",
  "",
  "本库**没有外键**（与进销存同一条通则：完整性由聚合根 + 唯一键兜底），下面是靠业务键推出来的：",
  "",
  "| 从 | 列 | 指向 |",
  "|---|---|---|",
  ...rels.map((r) => `| \`${r.from}\` | \`${r.col}\` | \`${r.to}\` |`),
  "",
  "> `buyer_ref` / `account_ref` 指向**主系统**的用户号，本库里没有那张表，所以图上没有这两条线。",
  "> 跨库只存外部键 —— 元器件独立出去时，换的是这两列的取值，不是结构。",
  "",
];

if (process.argv.includes("--check")) {
  const stale = [];
  if (!existsSync(SVG) || readFileSync(SVG, "utf8") !== svg) stale.push("docs/technical/diagrams/db-elec.svg");
  if (!existsSync(OUT) || readFileSync(OUT, "utf8") !== md.join("\n")) {
    stale.push("docs/technical/reference/数据库-元器件.md");
  }
  if (stale.length) {
    console.error(`✗ 元器件 ER 图与源码对不上：${stale.join("、")}\n  修：node scripts/gen-elec-erd.mjs`);
    process.exit(1);
  }
  console.log(`✓ 元器件 ER 图是最新的（${tables.size} 张表）`);
} else {
  mkdirSync(DIAG, { recursive: true });
  writeFileSync(SVG, svg);
  writeFileSync(OUT, md.join("\n"));
  console.log(`✓ ${tables.size} 张表 / ${rels.length} 条关系 → db-elec.svg 与 数据库-元器件.md`);
}
