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

/**
 * 纳入守卫的文件：v2 全集 + V2 工作流产出的深层册（按文件名特征挑，不扫历史文档）。
 *
 * ⚠️ **这是白名单，漏一个域＝那个域的文档从没被守过，而闸门照样报绿。**
 * 支付域的 11 份文档就这样在名单外待了一整轮 —— 加进来时零红，
 * 也就是说这段时间它们「没有违规」这件事，从来没有被验证过，
 * 只是没有人去看。加新域的设计册时**先把前缀加到这里**。
 * 下面 MIN_FILES 那条断言守的就是这个。
 */
const V2_DEEP = /^(TDD-(支付域|商品域|订单域|预约资源域|会员资产域|打印域|工作流领域模型|基础订单流|行业包|基座与行业应用|前端方案)|商品域V2|订单域V2|预约资源域V2|会员资产域V2|shop-industry-spi|能力开关A4|迁移与兼容手册|三行业场景|结算域-V2|库存域-V2|服务人员-收尾|SKU与规格库|权限码扩容单)/;
const files = [
  ...readdirSync(join(ROOT, "docs/v2")).filter((f) => f.endsWith(".md")).map((f) => join(ROOT, "docs/v2", f)),
  ...readdirSync(join(ROOT, "docs/technical/design")).filter((f) => f.endsWith(".md") && V2_DEEP.test(f))
    .map((f) => join(ROOT, "docs/technical/design", f)),
  join(ROOT, "docs/technical/reference/商品域-多行业对齐清单.md"),
  join(ROOT, "docs/technical/reference/核心能力清单.md"),
];

/*
 * **扫描面本身要有个下限。**
 *
 * 这个闸门是「找出违规」型的：扫得少 → 找不到违规 → 报绿。
 * 名单被改窄（重构正则、挪目录、手滑删一项）时，它不会喊，
 * 只会安静地少扫几十份文档然后打一个勾。
 *
 * 所以钉一个下限。数字取当前份数往下留一点余量：
 * 删几份旧文档不该让闸门红，而**一次少掉十几份一定是名单出了事**。
 */
const MIN_FILES = 50;
if (files.length < MIN_FILES) {
  console.error(`✗ 只扫到 ${files.length} 份文档，少于下限 ${MIN_FILES} —— ` +
    "多半是 V2_DEEP 白名单被改窄了，或者 docs/v2 目录挪了位置。\n" +
    "  这个闸门是「找出违规」型的：扫不到就报绿，所以少扫比误报危险。");
  process.exit(1);
}

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
/*
 * 支付域领域模型：**自称的对象数必须等于实际列出的**。
 *
 * 加这一条是因为写那份文档时这个数字连错三次（11 / 13 / 23 / 26 都写过），
 * 每次都是手数漏了一两行 —— 而<b>一个自己都数不对的清单，
 * 读的人凭什么信它是全的</b>。清单的价值全在「说的是真话」。
 *
 * 判据：九个分群表格里第一列加粗的那些行 = 对象。
 * 用 {1,} 而不是精确列数：分群表格是三列，将来加一列不该让它变红。
 */
const dm = join(ROOT, "docs/technical/design/TDD-支付域-领域模型（需求层）.md");
if (existsSync(dm)) {
  const src = read(dm);
  const listed = new Set([...src.matchAll(/^\| \*\*([^*|]+)\*\* \|/gm)].map((m) => m[1].trim()));
  const claim = src.match(/合计 (\d+) 个领域对象/);
  if (!claim) {
    fail(dm, "找不到「合计 N 个领域对象」那句 —— 判据靠它，改写法要同步改这里");
  } else if (listed.size !== Number(claim[1])) {
    fail(dm, `自称 ${claim[1]} 个领域对象，实际列出 ${listed.size} 个`);
  }
  // 扫描面断言：这份文档少说也有二十来个对象，扫出个位数一定是正则失配
  if (listed.size < 20) fail(dm, `只解析出 ${listed.size} 个对象行 —— 多半是表格写法变了，正则失配`);
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

// ── 5. 设计 ⇄ 真实 schema ──────────────────────────────────
// 判据取自生成物 schema-test.sql（主库全量）。它本身由 SchemaGeneratorTest 维护，
// 所以这一类查的是「设计册说的」与「库里真有的」是否对得上，而不是两份文档互相对。
const SCHEMA = join(ROOT, "backend/shop-app/src/test/resources/schema-test.sql");
if (existsSync(SCHEMA)) {
  const live = new Set(
    [...read(SCHEMA).matchAll(/CREATE TABLE (?:IF NOT EXISTS )?`?([A-Za-z_]+)`?/gi)]
      .map((m) => m[1].toLowerCase()),
  );

  // 规格书里标了「沿用」的表 —— 必须真在库里
  // ⚠️ 两张清单列数不同：商品域「表|层|状态」中间隔 1 列，订单域「表|状态」隔 0 列。
  // 反向验证连撞两次才定下 {0,1} —— 第一次写 {2}、第二次写 {1,2}，
  // 两次订单域都整表漏检，而守卫「看起来是绿的」。这正是恒绿闸门的样子。
  const CLAIM_ROW = (kw) => new RegExp(String.raw`\|\s*\`([a-z_]+)\`\s*\|(?:[^|\n]*\|){0,1}\s*(?:\*\*)?${kw}`, "g");
  const REUSE_CLAIMS = [["docs/v2/15-商品订单数据表总册.md", CLAIM_ROW("沿用")]];
  for (const [rel, re] of REUSE_CLAIMS) {
    const f = join(ROOT, rel);
    if (!existsSync(f)) continue;
    for (const m of read(f).matchAll(re)) {
      const t = m[1];
      if (!live.has(t)) fail(f, `声称「沿用」的表 ${t} 在 schema-test.sql 里不存在（设计假设落空？表名写错？）`);
    }
  }

  // 规格书里标了「新建」的表 —— 必须还不在库里（撞名会让迁移失败）
  for (const [rel, re] of [["docs/v2/15-商品订单数据表总册.md", CLAIM_ROW("新建")]]) {
    const f = join(ROOT, rel);
    if (!existsSync(f)) continue;
    for (const m of read(f).matchAll(re)) {
      const t = m[1];
      if (live.has(t)) fail(f, `标为「新建」的表 ${t} 库里已存在 —— 撞名，迁移会失败`);
    }
  }

  // DDL 里 ALTER 的目标表必须存在（加列加到不存在的表上，迁移当场炸）
  for (const rel of ["docs/technical/design/商品域V2-设计规格书.md",
                     "docs/technical/design/订单域V2-设计规格书.md",
                     "docs/v2/14-电子元器件行业规格.md"]) {
    const f = join(ROOT, rel);
    if (!existsSync(f)) continue;
    for (const m of read(f).matchAll(/ALTER TABLE ([a-z_]+)/g)) {
      const t = m[1];
      // 本轮新建的表也可能被 ALTER（如批次改造 inv_*），只校验主库已知前缀
      if (/^(prd|ord|cat|sell|mch|mbr|sys)_/.test(t) && !live.has(t) && !ALL_DEFINED.has(t)) {
        fail(f, `ALTER TABLE ${t}：该表既不在库里也不在 V2 规格书 DDL 里`);
      }
    }
  }
}

// ── 输出 ───────────────────────────────────────────────────
if (errs.length) {
  console.error(`❌ V2 文档守卫：${errs.length} 处问题\n`);
  for (const e of errs) console.error("  " + e);
  console.error("\n改文档后请同步：断链 / 表名 / 自称数字 / 能力码 / 设计与真实 schema 的一致性。");
  process.exit(1);
}
console.log(`✅ V2 文档守卫通过（${files.length} 份文档：断链 · 表名集合 · 自称数字 · 能力码 · 设计⇄真实 schema）`);
