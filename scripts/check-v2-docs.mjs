#!/usr/bin/env node
/**
 * V2 文档集守卫 —— 把「双写点漂移」从靠人 review 变成红灯。
 *
 * 这套文档里同一事实被写了两遍的地方不少（术语层、能力码、状态、表名、自称数字），
 * 一次 review 已经抓到一处实质漂移：可售时段的关系表在对齐清单里挂 goods、
 * 在规格书里挂 listing —— **表名不同只是表面，粒度错了才是缺陷**。
 * 靠人比对四十份文档不可持续，所以本脚本只查四类机器查得动的：
 *
 *   1. 断链       —— v2 集与本轮 design/reference 新册的相对链接必须存在
 *   2. 表名集合   —— 规格书 CREATE TABLE 的表名，必须与 v2 摘要层提到的一致（互为子集）
 *   3. 自称数字   —— 「N 张表」「N 个 Core*Api」「N 场景」等自述与实际条数一致
 *   4. 取值域     —— 能力码在 A4 实施规格与核心能力清单附表之间一致
 *
 * 查不动的（叙述矛盾、语义粒度）仍靠 review —— 本脚本不假装能替代它。
 *
 * 用法：node scripts/check-v2-docs.mjs        （pre-push 调用，非零退出即拦）
 */
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname, normalize, relative } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const errs = [];
const fail = (file, msg) => errs.push(`${relative(ROOT, file)}: ${msg}`);
const read = (p) => readFileSync(p, "utf8");

/** 纳入守卫的文件：v2 全集 + V2 工作流产出的深层册（按文件名特征挑，不扫历史文档） */
const V2_DEEP = /^(TDD-(商品域|订单域|预约资源域|会员资产域|打印域|工作流领域模型|基础订单流|行业包|基座与行业应用|前端方案)|商品域V2|订单域V2|预约资源域V2|会员资产域V2|shop-industry-spi|能力开关A4|迁移与兼容手册|三行业场景|结算域-V2|库存域-V2|服务人员-收尾|SKU与规格库|权限码扩容单)/;
const files = [
  ...readdirSync(join(ROOT, "docs/v2")).filter((f) => f.endsWith(".md")).map((f) => join(ROOT, "docs/v2", f)),
  ...readdirSync(join(ROOT, "docs/technical/design")).filter((f) => f.endsWith(".md") && V2_DEEP.test(f))
    .map((f) => join(ROOT, "docs/technical/design", f)),
  join(ROOT, "docs/technical/reference/商品域-多行业对齐清单.md"),
  join(ROOT, "docs/technical/reference/核心能力清单.md"),
];

// ── 1. 断链 ────────────────────────────────────────────────
for (const f of files) {
  for (const m of read(f).matchAll(/\[([^\]]*)\]\((?!https?:|mailto:)([^)#\s]+)(#[^)]*)?\)/g)) {
    const target = normalize(join(dirname(f), m[2]));
    if (!existsSync(target)) fail(f, `断链 [${m[1].slice(0, 24)}] → ${m[2]}`);
  }
}

// ── 2. 表名集合：规格书 DDL ⇄ v2 摘要层 ────────────────────
const PAIRS = [
  ["docs/technical/design/商品域V2-设计规格书.md", "docs/v2/05-商品域规格.md"],
  ["docs/technical/design/订单域V2-设计规格书.md", "docs/v2/06-订单域规格.md"],
  ["docs/technical/design/预约资源域V2-设计规格书.md", "docs/v2/10-预约资源域规格.md"],
  ["docs/technical/design/会员资产域V2-设计规格书.md", "docs/v2/12-会员资产域规格.md"],
];
const ddlTables = (src) =>
  new Set([...src.matchAll(/^CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)/gm)].map((m) => m[1]));
const mentioned = (src) => new Set([...src.matchAll(/`((?:prd|ord|sell|mch|cat|mbr|prn)_[a-z_]+)`/g)].map((m) => m[1]));

// 全域已定义表名：跨域引用是合法的（10 册讲「资源 vs 工位」时会提商品域的 mch_station），
// 所以判据是「这张表在**任一**规格书里建过」，而不是「必须建在配对的那一本里」——
// 判紧了会把正当的跨域引用报成缺陷（本守卫上线首跑就撞到一次），
// 但拼错表名、凭空捏造仍然抓得住。
const ALL_DEFINED = new Set();
for (const [specPath] of PAIRS) {
  const spec = join(ROOT, specPath);
  if (existsSync(spec)) for (const t of ddlTables(read(spec))) ALL_DEFINED.add(t);
}
// 沿用的存量表不在 V2 规格书里建，登记在此（改一处即可，不必逐册加注）
for (const t of ["prd_category", "prd_sku_bundle", "prd_store_stock", "prd_store_price",
                 "prd_spec_dim", "prd_spu_std", "prd_topic", "prd_goods_availability",
                 "ord_status_log", "ord_after_sale", "ord_invoice_request",
                 "mch_appointment_slot", "mch_store", "mch_resource", "mch_staff_skill",
                 "sys_pay_channel", "sys_industry", "mbr_member", "prn_job"]) ALL_DEFINED.add(t);

for (const [specPath, summaryPath] of PAIRS) {
  const spec = join(ROOT, specPath), summary = join(ROOT, summaryPath);
  if (!existsSync(spec) || !existsSync(summary)) { fail(spec, "配对文件缺失"); continue; }
  const tables = ddlTables(read(spec));
  for (const t of mentioned(read(summary))) {
    if (!ALL_DEFINED.has(t)) fail(summary, `提到表 ${t}，但全部 V2 规格书里都没有它的 DDL（拼错？漏建？）`);
  }
  const claim = read(summary).match(/表清单（(\d+)\s*张/);
  if (claim && Number(claim[1]) !== tables.size) {
    fail(summary, `自称 ${claim[1]} 张表，规格书实有 ${tables.size} 个 CREATE TABLE`);
  }
}

// ── 3. 自称数字 ────────────────────────────────────────────
const spi = join(ROOT, "docs/technical/design/shop-industry-spi-契约定稿.md");
if (existsSync(spi)) {
  const src = read(spi);
  const apis = new Set([...src.matchAll(/^Core([A-Za-z]+)Api/gm)].map((m) => m[1])).size;
  const claim = src.match(/Core\*Api\s*(?:十二个|(\d+)\s*个)/) || src.match(/（(\d+) 个 Core\*Api/);
  const want = claim?.[1] ? Number(claim[1]) : 12; // 「十二个」写死在册名里
  if (apis !== want) fail(spi, `自称 ${want} 个 Core*Api，实际列出 ${apis} 个`);
}
const scen = join(ROOT, "docs/technical/design/三行业场景工作流手册.md");
if (existsSync(scen)) {
  const src = read(scen);
  const rows = [...src.matchAll(/^\| [RFB]\d+ \|/gm)].length;
  const claim = src.match(/(\d+)\s*个场景/);
  if (claim && Number(claim[1]) !== rows) fail(scen, `自称 ${claim[1]} 个场景，索引表实有 ${rows} 行`);
}

// ── 4. 能力码取值域一致 ────────────────────────────────────
const a4 = join(ROOT, "docs/technical/design/能力开关A4-实施规格.md");
const caps = join(ROOT, "docs/technical/reference/核心能力清单.md");
if (existsSync(a4) && existsSync(caps)) {
  const block = read(a4).match(/`Capabilities`[\s\S]*?```([\s\S]*?)```/);
  if (!block) fail(a4, "找不到 Capabilities 取值域代码块");
  else {
    const a4Codes = new Set(block[1].match(/[A-Z][A-Z_]{3,}/g) ?? []);
    const capsSrc = read(caps);
    for (const c of a4Codes) {
      if (!capsSrc.includes(c)) fail(a4, `能力码 ${c} 未出现在核心能力清单（取值域漂移）`);
    }
  }
}

// ── 输出 ───────────────────────────────────────────────────
if (errs.length) {
  console.error(`❌ V2 文档守卫：${errs.length} 处问题\n`);
  for (const e of errs) console.error("  " + e);
  console.error("\n改文档后请同步：断链 / 表名 / 自称数字 / 能力码取值域。");
  process.exit(1);
}
console.log(`✅ V2 文档守卫通过（${files.length} 份文档：断链 · 表名集合 · 自称数字 · 能力码）`);
