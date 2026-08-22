// 运营端数据域接入的三条结构性守卫（TDD-运营端数据域接入 §4.2）。
//
// **背景**：`sys_ops_staff` 上的 merchant_no / community_no / pickup_no 存了但没人读 ——
// 它算得出真实的 `DataScopeSpec` 并签进 `LoginUser`，而每一条 ops 查询都
// `executeWithoutScope` 主动绕开了。结果是「给客服配了只看城西片区，他照样看到全平台的单」。
//
// 接入这件事有两个方向的坑，两条都不报错：
//   ① **漏接**：某条 ops 查询忘了去掉 `executeWithoutScope` → 越权数据照常返回。
//      没有任何信号 —— 它跟「本来就该看全量」长得一模一样。          → G1 / G2
//   ② **接过头**：一张表注册了，却没为运营会话登记锚点列 → `DataScopeHandler`
//      是 **fail-closed** 的，它拼的是 `1=0` 而不是放行。那一类运营**整页空白**，
//      而空白看起来像「这个片区没有数据」，不像故障。                → G3
//
// G3 是最关键的一条：G1/G2 全绿时，一张注册了但缺锚点的表仍然会让运营整页空白。
// 类注释里那条教训（DataScopeRegistration）写得很清楚：订单表登记了 MERCHANT 却漏了
// SELF，C 端「我的订单」立刻空列表。运营端接入会重演同一形状。
//
// ── 为什么只数「已注册表」上的绕过 ──
// `DataScopeHandler` 对**未注册的表直接返回 null（放行）**，所以对未注册表调用
// `executeWithoutScope` 是**字面意义上的空操作**。全仓 500+ 处绕过里绝大多数属于这类，
// 把它们都算进来只会淹掉真正的那二十几处。
// 反过来这也带来一个很有用的耦合：**注册一张新表，会立刻把所有绕过它的 ops 查询变红** ——
// 分批推进时不需要人去记「这一批还要改哪些 Service」，守卫会点名。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BACKEND = join(ROOT, "backend");
const MODULES = ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel", "shop-base"];
const REGISTRATION = join(
  BACKEND, "shop-app/src/main/java/ai/neargo/shop/config/DataScopeRegistration.java");
const SCHEMA = join(BACKEND, "shop-app/src/test/resources/schema-test.sql");

/** 运营会话可能带的三个维度（`StaffIdentityResolver.scopeOf`）。SELF/GROUP 是 C 端的，不在此列。 */
const OPS_DIMS = ["MERCHANT", "COMMUNITY", "PICKUP"] as const;

/**
 * **豁免登记表（G1）**：这些绕过点已经看过，确认要留着。key 是 `类#方法`。
 *
 * 判断标准只有一条：**这次查询是不是「决定行级可见性」的那一次**。
 *   · 是（列表 / 分页 / 检索）→ 必须接数据域，不许登记在这里
 *   · 不是（拿着已授权那一行的主键去回捞明细、补字段）→ 留着绕过是对的：
 *     可见性上一步已经判完，这一步再判一次只会把「查得到但字段是空的」变成新的故障形状
 *
 * 写路径（改状态、处置）也留着绕过，理由见 TDD §5 T2：写操作的越权由
 * `@PreAuthorize` + Service 内归属校验挡，走数据域会把「处置一家不在自己域内的商家」
 * 变成**静默失败**，而静默失败比明确拒绝更坏。
 * 不过写端点本来就不在 G1 的扫描范围里（G1 只看 GET），所以它们不必登记。
 */
const SCOPE_BYPASS_OK: Record<string, string> = {
  // ── 批① ord_sub_order 之后留下的 ──
  "MerchantOrderServiceImpl#toOpsVO":
    "按子单主键回捞主单（ord_order）补社区字段。行级可见性已由上一步的子单查询判定；"
    + "这里再判一次的效果是「单查得到、社区列是空的」——把一次拒绝变成一处脏数据",

  // ── B 端与 ops 共用同一个方法，**不能只看 ops 一侧就把绕过去掉** ──
  // B 端会话带的是 SELF 维度（买家 user_no），而 ord_sub_order 的 SELF 锚点正是 user_no：
  // 去掉绕过，商家看板会只剩「商家本人作为买家下的单」，也就是几乎全空。
  // 要接的话得先拆出 ops 专用口径 —— 排在批④收尾。
  "MerchantOrderServiceImpl#todo":
    "B 端商家看板与 ops 门店统计共用。B 端会话是 SELF 维度，去掉绕过等于把商家看板清空。批④ 拆口径",
  "MerchantOrderServiceImpl#scan":
    "同 #todo：ops 门店统计与 B 端共用的扫描口径。批④ 拆口径",

  // ── 商品域批⑤（2026-08-21）：列表批量化之后新出现的两处 ──
  //
  // 两处都**只是拿已经被数据域裁过的 goodsNo 再读一遍那几行**，不扩大可见范围：
  // 分页查询（auditQueue）本身是接数据域的，detailAll 收到的 id 集合已经是授权结果。
  // 去掉绕过反而会坏事 —— detailAll 与 list 同时服务 B 端（SELF 维度），
  // 而 prd_goods 只有 MERCHANT 锚点，接上就是 1=0，商家商品列表当场全空。
  "GoodsServiceImpl#detailAll":
    "按已授权的 goodsNo 批量回读商品行，组装 VO 用。id 集合来自上一步已接数据域的分页查询；"
    + "再裁一次不增加安全性，却会让 B 端（SELF 维度）的同一条路径拼出 1=0",
  // 读 SKU 的三处：输入都是**已经被数据域裁过的 goodsNo**（或 B 端自己的 merchantNo），
  // 拿它们去补价、补库存。再裁一次不增加安全性，却会让 B 端（SELF 维度）拼出 1=0。
  //
  // ⚠️ 真正的口子不在这三处，而在 `PlatformProductServiceImpl#listSkus` /
  // `#listOversellSkus` —— 那两条是**直接检索 prd_sku**，2026-08-21 已去掉绕过接上数据域。
  "GoodsServiceImpl#loadSkus":
    "按 goodsNo 批量取 SKU 组装展示价。goodsNo 集合来自已接数据域的商品查询；"
    + "且这是买家侧公共目录的同一条路径（C 端会话是 SELF 维度），接上就是 1=0",
  "MerchantGoodsServiceImpl#withMarketPrices":
    "按 goodsNo 补各市场价（商家侧编辑页整份回填要用）。同上：id 已授权，再裁只会把 B 端清空",
  "MerchantGoodsServiceImpl#outOfStockGoodsNos":
    "按 merchantNo 算「所有 SKU 都缺货」的商品号，只服务 B 端的缺货页签。"
    + "B 端会话是 SELF 维度，接上数据域这一筛恒为空 —— 商家的缺货页签会永远显示没有缺货",

  "MerchantGoodsServiceImpl#list":
    "B 端商家商品列表与运营审核队列共用的读口径。B 端会话是 SELF 维度，"
    + "而 prd_goods 只有 MERCHANT 锚点 —— 去掉绕过，商家的商品列表当场全空。"
    + "运营侧真正接数据域的是 auditQueue 与 listForOps 那两条",

  // ── 批②（2026-08-14）：主查询已接数据域，剩下的是**装饰性取名** ──
  //
  // 这几处的输入是**已经过数据域裁剪的主查询的结果**（商家号/门店号），
  // 拿它们去补一个名字。再裁一次不增加任何安全性 —— 能拿到 ID 就说明已被授权 ——
  // 而**裁错了的后果是列表里名字变成空白**，比不裁更难查。
  //
  // ⚠️ 判据是「输入 ID 来自已授权的主查询」。若将来有人拿它做主查询（比如直接
  // 按关键字搜商家名），这条豁免就不成立了 —— 那时该拆一个新方法，而不是复用它。
  "MerchantPortImpl#findAll":
    "装饰性取名：入参是已授权主查询给出的商家号集合，用来补 merchantName",
  "MerchantPortImpl#find":
    "跨域 Port 的单个商家查询。**调用方各带各的维度**（C 端买家是 SELF、B 端商家是 SELF），"
    + "而 mch_entity 只有 MERCHANT 锚点 —— 不豁免的话下单时取不到商家名快照，"
    + "分拣汇总里商家名会是空串（批② 实测）。ops 侧用它同样是装饰性取名，主查询已裁剪",
  "MerchantPortImpl#entityOfStores":
    "同上：门店号 → 主体号的反查，入参来自已授权的查询",
  // 方案 v4（2026-08-22）：ops 商家履约视图 → 准入矩阵 denied 判定 → 自提点归属比对。
  // 入参是路径参数 merchantNo（页面上下文已授权的那家），读 mch_entity 只为拿
  // owner_user_no 做「供货方=自提点运营者」的比对 —— 按已授权主键回捞明细那一类
  "MerchantPortImpl#ownerUserNoOf":
    "按已授权的 merchantNo 回捞 owner_user_no，供准入矩阵降级判定；不是检索入口",
  "MerchantGovernServiceImpl#nameOf":
    "同上：违规记录列表补商家名",
  "MerchantStaffServiceImpl#storeNames":
    "同上：员工的门店授权列表补门店名",

  // ── 批③（2026-08-14 完成）：prd_goods 上的 ops 只读查询已接数据域 ──
  //
  // 接上的三条不在这张表里了（列在这里只为说明批③ 到底动了什么）：
  //   · MerchantGoodsServiceImpl#auditQueue     —— 新拆出来的 ops 待审队列
  //   · MerchantGoodsServiceImpl#listForOps     —— 运营商品池
  //   · MerchantGoodsServiceImpl#requireByNoInScope —— 运营商品详情
  // 剩下这三条是**永久豁免**，不是欠着的：
  "GoodsServiceImpl#detail":
    "C 端公共目录（MpCatalogController 是唯一调用方）。C 端会话是 SELF 维度，"
    + "而 prd_goods 只有 MERCHANT 锚点 —— 接上的症状是「游客能逛、一登录就一件商品都看不见」，"
    + "且不报错、日志干净。正确做法是豁免，不是给商品表编一个假的 SELF 锚点",
  // `MerchantGoodsServiceImpl#list` 与 `#requireByNo` **刻意不在这张表里**：
  // 批③ 之后它们已经不在任何 ops 查询路径上（前者只剩 B 端商家列表，后者只剩写路径），
  // 而 G2 会把留在这里的条目当死条目报出来。它们各自为什么仍然豁免，写在方法上。
  "PlatformProductServiceImpl#toVOs":
    "装饰性回填：入参是已授权主查询给出的 skuNo/goodsNo 集合，用来补商品名与多市场价",

  // ── 批④（2026-08-14 完成）：结算与履约的 ops 查询已接数据域 ──
  //
  // 接上的四条不在这张表里了：SettleServiceImpl#opsBills / #opsPayables、
  // FulfillmentStatsPortImpl#openPickupOrders / #expressOrders。
  // 剩下这两条是**永久豁免**：
  "RefundSplitBackServiceImpl#pending":
    "按已取到的 subOrderNo 集合回捞结算单状态，用来把已 REVERSED 的从队列里剔掉。"
    + "⚠️ 这条队列真正的边界在 ord_after_sale 上，而**那张表还没登记数据域**（未注册 = 放行）—— "
    + "也就是说配了商家域的财务在这个待办里仍看得到全量。把 bill 这一步接上域并不能修好它，"
    + "只会让行上的状态变成空。要修得先给 ord_after_sale 定锚点，那是独立一步",
  "SelfOperatedExposurePortImpl#selfOperatedExposure":
    "装饰性回填：入参 entityNos 来自已授权的主查询（无照自营风险清单，批② 已接域），"
    + "这里只是按这批主体号求敞口金额",
};

/**
 * **锚点豁免登记表（G3）**：`表:维度` → 为什么这张表在这个维度上没有锚点列，
 * 以及**代价是什么**。
 *
 * ⚠️ 每加一条，都等于承认「配了这个维度的运营账号打开这张表相关的页面是空白」。
 * 所以理由里必须写清楚**谁会看到空白**——这份清单要交给运营团队（TDD Q2）。
 */
const ANCHOR_WAIVED: Record<string, string> = {
  "ord_order:MERCHANT":
    "主单跨商家（一次结算拆成多个商家的子单），没有单一 entity_no。"
    + "运营端不直接列主单，只经已授权子单按主键回捞 —— 见 SCOPE_BYPASS_OK 的 toOpsVO",
  "ord_order:PICKUP": "同上：自提点挂在子单上，主单没有",

  "prd_goods:COMMUNITY":
    "商品属于商家而不属于社区（可售社区在 prd_goods_pool，多值）。"
    + "代价：配了社区域的运营打开商品池是空白。批③ 要正面解决，现在 prd_goods 上的 ops 查询"
    + "仍全部绕过数据域（SCOPE_BYPASS_OK），所以这条暂时不产生实际影响",
  "prd_goods:PICKUP": "同上：商品不属于自提点",

  // prd_sku 于 2026-08-21 补登记（商品域优化清单 P2-4）：此前它**根本没注册**，
  // 于是不带过滤条件的 `GET /ops/skus` 是全平台可见，配了商家域的运营也一样。
  // 两个维度的豁免理由与 prd_goods 同源 —— SKU 挂在商品上，商品挂在商家上。
  "prd_sku:COMMUNITY":
    "SKU 属于商品、商品属于商家，与社区无关（可售社区在 prd_community_pool，多值）。"
    + "代价：配了社区域的运营打开「库存与预售」tab 是空白 —— 与商品池同一批人、同一天暴露",
  "prd_sku:PICKUP": "同上：SKU 不属于自提点",

  "stl_bill:COMMUNITY": "结算单按商家出账，与社区无关。代价：配了社区域的运营打开结算页空白（批④）",
  "stl_bill:PICKUP": "同上",

  "ful_group_pickup:MERCHANT": "邻里自提团的作用域是单个团（ADR-005），与商家无关",
  "ful_group_pickup:COMMUNITY": "同上；团挂在自提点上，PICKUP 维度已登记",

  // ── 批②（2026-08-14）：mch_entity / mch_store 只登记 MERCHANT 锚点 ──
  //
  // COMMUNITY 与 PICKUP **刻意不登记**，理由不是「缺一列」，是**没有角色需要它**：
  // 持社区域的 COMMUNITY_OPS 与持自提点域的角色，权限码里都没有 merchant:*，
  // 而 GET /ops/stores 与 /ops/merchants 都要 merchant:merchant:read ——
  // 它们进不了这两个页面，登记 COMMUNITY 锚点是为一个不存在的场景加冗余数据。
  //
  // （曾想给 mch_store 加一列 community_no。除了上面这条，还有一条硬伤：
  //  门店的社区是多值的 —— 一家店可在多社区各挂一个自提点，取其中一个的后果是
  //  「另一个社区的运营看不到这家店」，比整页空白更难发现。见 TDD §6.1。）
  //
  // ⚠️ 若将来给社区运营开了 merchant:merchant:read，这两条豁免立刻变成
  // 「那个角色打开门店/商家档案是空白」—— 到时要回 TDD §6.1 重选方案，不是直接加列。
  "mch_entity:COMMUNITY":
    "没有持社区域的角色能读商家档案（COMMUNITY_OPS 无 merchant:* 码）。若将来开了，这里会变成空白页",
  "mch_entity:PICKUP": "同上：没有持自提点域的角色能读商家档案",
  "mch_store:COMMUNITY":
    "同 mch_entity；且门店的社区本身是多值的（一店可在多社区挂自提点），单列锚点表达不了",
  "mch_store:PICKUP": "同上",
  // 方案 v4（2026-08-22）：门店送货方式与门店同一归属逻辑，同一批豁免理由
  "mch_fulfillment_channel:COMMUNITY":
    "同 mch_store：没有持社区域的角色能读商家履约配置（merchant:merchant:read 不在 COMMUNITY_OPS）。若将来开了，这里会变成空白页",
  "mch_fulfillment_channel:PICKUP": "同上：没有持自提点域的角色能读商家履约配置",
};

// ────────────────────────────────────────────────── 静态扫描

const KEYWORDS = new Set(["if", "for", "while", "switch", "catch", "try", "synchronized",
  "return", "new", "do", "else"]);

interface ClassInfo {
  src: string;
  /** 字段名 → 类型简名 */
  fields: Map<string, string>;
  /** 方法名 → 方法体（含大括号）。重载合并成多份 */
  methods: Map<string, string[]>;
}

function javaFiles(): string[] {
  const out: string[] = [];
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith(".java")) out.push(p);
    }
  };
  for (const m of MODULES) walk(join(BACKEND, m, "src/main/java"));
  return out;
}

function matchClose(src: string, from: number, open: string, close: string): number {
  let depth = 0;
  for (let i = from; i < src.length; i++) {
    if (src[i] === open) depth++;
    else if (src[i] === close) { depth--; if (depth === 0) return i; }
  }
  return src.length;
}

/** 粗切方法体。够用：这里要的只是「这段代码里出现了什么」，不需要真的解析 Java */
function methodBodies(src: string): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const re = /\n[ \t]+(?:public|private|protected|static|final|default|synchronized|abstract|\s)*[A-Za-z0-9_<>,.[\]?\s]+\s+(\w+)\s*\((?:[^;{)]|\([^)]*\))*\)\s*(?:throws [\w,.\s]+)?\{/g;
  for (let m: RegExpExecArray | null; (m = re.exec(src)); ) {
    const name = m[1]!;
    if (KEYWORDS.has(name)) continue;
    const open = src.indexOf("{", m.index + m[0].length - 1);
    const body = src.slice(open, matchClose(src, open, "{", "}") + 1);
    if (!out.has(name)) out.set(name, []);
    out.get(name)!.push(body);
  }
  return out;
}

const classes = new Map<string, ClassInfo>();
/** 接口/父类简名 → 实现类简名[] */
const implOf = new Map<string, string[]>();
/** Mapper 简名 → 实体简名 */
const mapperEntity = new Map<string, string>();
/** 实体简名 → 表名 */
const entityTable = new Map<string, string>();

for (const f of javaFiles()) {
  const src = readFileSync(f, "utf8");
  const name = f.split("/").pop()!.replace(/\.java$/, "");
  const decl = src.match(new RegExp(`(?:class|interface|record|enum)\\s+${name}\\b([^{]*)\\{`));
  if (decl) {
    for (const m of decl[1]!.matchAll(/\b(?:implements|extends)\s+([A-Za-z0-9_.<>,\s]+)/g)) {
      for (const t of m[1]!.split(",")) {
        const tn = t.trim().replace(/<.*/, "").split(".").pop();
        if (!tn) continue;
        if (!implOf.has(tn)) implOf.set(tn, []);
        implOf.get(tn)!.push(name);
      }
    }
  }
  const fields = new Map<string, string>();
  // 类型可能带包名（`private final ai.neargo.x.FooService foo;`）—— 取简名
  for (const m of src.matchAll(
    /(?:private|protected|public)\s+(?:final\s+)?((?:\w+\.)*[A-Z][A-Za-z0-9_]*)(?:<[^;=]*>)?\s+(\w+)\s*[;=]/g)) {
    fields.set(m[2]!, m[1]!.split(".").pop()!);
  }
  classes.set(name, { src, fields, methods: methodBodies(src) });

  for (const m of src.matchAll(/interface\s+(\w+)\s+extends\s+BaseMapper<(\w+)>/g)) {
    mapperEntity.set(m[1]!, m[2]!);
  }
  const tn = src.match(/@TableName\("(\w+)"\)/);
  if (tn) entityTable.set(name, tn[1]!);
}

function resolveTypes(t: string): string[] {
  const out: string[] = [];
  if (classes.has(t)) out.push(t);
  for (const c of implOf.get(t) ?? []) if (!out.includes(c)) out.push(c);
  return out;
}

/** 一段代码摸到了哪些表（顺着字段调用往下走几层） */
function tablesIn(cls: string, region: string, depth = 0, seen = new Set<string>()): Set<string> {
  const out = new Set<string>();
  const c = classes.get(cls);
  if (!c || depth > 3) return out;
  for (const m of region.matchAll(/(\w+)\.(\w+)\s*\(/g)) {
    const ft = c.fields.get(m[1]!);
    if (!ft) continue;
    if (mapperEntity.has(ft)) {
      const t = entityTable.get(mapperEntity.get(ft)!);
      if (t) out.add(t);
      continue;
    }
    for (const tc of resolveTypes(ft)) {
      const key = `${tc}#${m[2]}`;
      if (seen.has(key)) continue;
      seen.add(key);
      for (const b of classes.get(tc)?.methods.get(m[2]!) ?? []) {
        for (const t of tablesIn(tc, b, depth + 1, seen)) out.add(t);
      }
    }
  }
  for (const m of region.matchAll(/(?<![\w.])(\w+)\s*\(/g)) {
    if (KEYWORDS.has(m[1]!)) continue;
    const key = `${cls}#${m[1]}`;
    if (seen.has(key)) continue;
    for (const b of c.methods.get(m[1]!) ?? []) {
      seen.add(key);
      for (const t of tablesIn(cls, b, depth + 1, seen)) out.add(t);
    }
  }
  return out;
}

/** 从 (类, 方法) 出发，能走到的「绕过了数据域、且摸的是已注册表」的点 → 表名[] */
function bypassSites(cls: string, mname: string, registered: Set<string>,
                     seen = new Set<string>(), depth = 0): Map<string, string[]> {
  const sites = new Map<string, string[]>();
  const c = classes.get(cls);
  if (!c || depth > 6) return sites;
  const key = `${cls}#${mname}`;
  if (seen.has(key)) return sites;
  seen.add(key);
  for (const body of c.methods.get(mname) ?? []) {
    let idx = 0;
    while ((idx = body.indexOf("executeWithoutScope", idx)) >= 0) {
      const open = body.indexOf("(", idx);
      const end = matchClose(body, open, "(", ")");
      const hit = [...tablesIn(cls, body.slice(open, end + 1))].filter((t) => registered.has(t));
      if (hit.length) sites.set(key, [...new Set([...(sites.get(key) ?? []), ...hit])]);
      idx = end;
    }
    for (const m of body.matchAll(/(\w+)\.(\w+)\s*\(/g)) {
      const ft = c.fields.get(m[1]!);
      if (!ft || mapperEntity.has(ft)) continue;
      for (const tc of resolveTypes(ft)) {
        for (const [k, v] of bypassSites(tc, m[2]!, registered, seen, depth + 1)) sites.set(k, v);
      }
    }
    for (const m of body.matchAll(/(?<![\w.])(\w+)\s*\(/g)) {
      if (KEYWORDS.has(m[1]!) || m[1] === mname || !c.methods.has(m[1]!)) continue;
      for (const [k, v] of bypassSites(cls, m[1]!, registered, seen, depth + 1)) sites.set(k, v);
    }
  }
  return sites;
}

/** 所有 `/ops/**` 端点（含动词、所属 Controller 类与方法名） */
function opsEndpoints() {
  const out: { verb: string; path: string; cls: string; method: string }[] = [];
  for (const [name, c] of classes) {
    if (!name.endsWith("Controller")) continue;
    const base = c.src.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/)?.[1] ?? "";
    const marks: { i: number; verb: string; raw: string }[] = [];
    const re = /@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"\)/g;
    for (let m: RegExpExecArray | null; (m = re.exec(c.src)); ) {
      marks.push({ i: m.index, verb: m[1]!.toUpperCase(), raw: m[2]! });
    }
    for (let k = 0; k < marks.length; k++) {
      const raw = marks[k]!.raw;
      const path = raw.startsWith("/ops") ? raw : (base.startsWith("/ops") ? base + raw : null);
      if (!path) continue;
      const win = c.src.slice(marks[k]!.i, k + 1 < marks.length ? marks[k + 1]!.i : c.src.length);
      const method = win.match(/\n[ \t]+(?:public|protected)[\w<>,.[\]\s?]*\s(\w+)\s*\(/)?.[1];
      if (method) out.push({ verb: marks[k]!.verb, path, cls: name, method });
    }
  }
  return out;
}

/** DataScopeRegistration.java → 表名 → { 维度: 锚点列 } */
function registeredTables(): Map<string, Record<string, string>> {
  const src = readFileSync(REGISTRATION, "utf8");
  const out = new Map<string, Record<string, string>>();
  for (const m of src.matchAll(/registry\.register\(\s*"(\w+)"\s*,\s*Map\.of\(([\s\S]*?)\)\s*\)\s*;/g)) {
    const anchors: Record<string, string> = {};
    for (const a of m[2]!.matchAll(/ScopeDim\.(\w+)\s*,\s*"(\w+)"/g)) anchors[a[1]!] = a[2]!;
    out.set(m[1]!, anchors);
  }
  return out;
}

/** schema-test.sql → 表名 → 列名集合。**逐表切块**，不做全局 grep ——
 *  `status` 这种列名到处都是，全局 grep 会假阳性通过。 */
function schemaColumns(): Map<string, Set<string>> {
  const src = readFileSync(SCHEMA, "utf8");
  const out = new Map<string, Set<string>>();
  for (const m of src.matchAll(/CREATE TABLE IF NOT EXISTS (\w+)\s*\(([\s\S]*?)\n\);/g)) {
    const cols = new Set<string>();
    for (const line of m[2]!.split("\n")) {
      const t = line.trim();
      if (!t || /^(PRIMARY|CONSTRAINT|UNIQUE|KEY|INDEX)\b/i.test(t)) continue;
      const c = t.split(/\s+/)[0];
      if (c && /^\w+$/.test(c)) cols.add(c);
    }
    out.set(m[1]!, cols);
  }
  return out;
}

// ────────────────────────────────────────────────── 守卫

describe("运营端数据域接入", () => {
  const registry = registeredTables();
  const registered = new Set(registry.keys());
  const schema = schemaColumns();
  const endpoints = opsEndpoints();
  const gets = endpoints.filter((e) => e.verb === "GET");

  it("解析没失效 —— 扫不到东西时不能静默通过", () => {
    expect(classes.size, "一个 Java 类都没扫到，目录结构变了？").toBeGreaterThan(300);
    expect(gets.length, "一条 ops 查询端点都没扫到，正则或注解写法变了？").toBeGreaterThan(50);
    expect(registered.size, "DataScopeRegistration 一张表都没解析出来").toBeGreaterThan(4);
    expect(schema.size, "schema-test.sql 一张表都没解析出来").toBeGreaterThan(50);
    // 绕过点的解析也要能动：这里断的是「扫得到 executeWithoutScope 这个符号」，
    // 而不是「必须有多少处」—— 后者接通完就会变成 0，是合法状态
    expect(
      [...classes.values()].filter((c) => c.src.includes("executeWithoutScope")).length,
      "全仓一处 executeWithoutScope 都没扫到 —— 十有八九是文件遍历坏了",
    ).toBeGreaterThan(20);
  });

  it("★★★ G1 ops 查询端点不许绕过已注册表的数据域 —— 绕过了就是越权，而且不报错", () => {
    const offenders: string[] = [];
    for (const e of gets) {
      for (const [site, tables] of bypassSites(e.cls, e.method, registered)) {
        if (site in SCOPE_BYPASS_OK) continue;
        offenders.push(`GET ${e.path} → ${site}（${tables.join("/")}）`);
      }
    }
    expect(
      [...new Set(offenders)].sort(),
      "这些 ops 查询走到了 `executeWithoutScope`，而它包住的表**已经注册进数据域**。\n"
      + "  后果：给这个运营配的数据域对这条查询完全不生效 —— 配了「只看某商家」的人照样看到全量，\n"
      + "  而页面上没有任何线索说明这一点。\n"
      + "  两条路：把这处 `executeWithoutScope` 去掉（查询路径应当接数据域）；\n"
      + "  或者确认它是「按已授权主键回捞明细」那一类，登记进 SCOPE_BYPASS_OK 并写清楚理由。",
    ).toEqual([]);
  });

  it("★★ G2 豁免登记表不能有死条目 —— 留着的害处是它下面那句理由会变成谎话", () => {
    const live = new Set<string>();
    for (const e of gets) for (const k of bypassSites(e.cls, e.method, registered).keys()) live.add(k);
    const dead = Object.keys(SCOPE_BYPASS_OK).filter((k) => !live.has(k));
    expect(
      dead.sort(),
      "这些登记项已经不成立了：要么方法没了/改名了，要么它绕过的表不再注册，\n"
      + "  要么它已经接上数据域、或已经不在任何 ops 查询的路径上 —— 从 SCOPE_BYPASS_OK 删掉。\n"
      + "  留着会让下一个人以为这块还没接，从而绕开它另做一套。",
    ).toEqual([]);
  });

  it("★★★ G3 已注册表必须真的存在 —— 注册一张不存在的表，那条登记就是一句谎话", () => {
    const ghosts = [...registered].filter((t) => !schema.has(t));
    expect(
      ghosts.sort(),
      "DataScopeRegistration 登记了这些表，而 schema-test.sql 里没有它们。\n"
      + "  登记不存在的表不会报错（没有查询会碰到它），但它会让人以为这块已经防住了。",
    ).toEqual([]);
  });

  it("★★★ G3 每张已注册表都要为运营会话的三个维度备好锚点 —— 缺一个，那类运营整页空白且不报错", () => {
    const holes: string[] = [];
    for (const [table, anchors] of registry) {
      for (const dim of OPS_DIMS) {
        const col = anchors[dim];
        if (col) continue;
        if (`${table}:${dim}` in ANCHOR_WAIVED) continue;
        holes.push(`${table}:${dim}`);
      }
    }
    expect(
      holes.sort(),
      "这些「已注册表 × 运营维度」上没有锚点列。`DataScopeHandler` 是 **fail-closed** 的：\n"
      + "  当前会话的维度在锚点里一个都找不到时，它拼的是 `1=0` 而不是放行。\n"
      + "  于是配了这个维度的运营账号打开相关页面**整页空白，且不报错** ——\n"
      + "  而空白看起来像「这个片区没有数据」，不像故障。\n"
      + "  两条路：给这张表补上锚点列（没有就加冗余列并回填，照 mch_store.community_no 的做法）；\n"
      + "  或者登记进 ANCHOR_WAIVED，**并在理由里写明谁会看到空白** —— 那份清单要交给运营团队。",
    ).toEqual([]);
  });

  it("★★★ G3 锚点列必须在该表的 DDL 里真的存在 —— 拼错列名只会静默拼出 1=0", () => {
    const bad: string[] = [];
    for (const [table, anchors] of registry) {
      const cols = schema.get(table);
      if (!cols) continue;   // 上一条已经点名了
      for (const [dim, col] of Object.entries(anchors)) {
        if (!cols.has(col)) bad.push(`${table}.${col}（${dim}）`);
      }
    }
    expect(
      bad.sort(),
      "这些锚点列在 schema-test.sql 的对应建表语句里不存在。\n"
      + "  **逐表核对，不是全局 grep** —— `status` 这种列名到处都是，全局 grep 会假阳性通过。\n"
      + "  最常见的成因：迁移里加了列，忘了 `python3 backend/scripts/gen-test-schema.py`。",
    ).toEqual([]);
  });

  it("★★ G3 锚点豁免登记表不能有死条目", () => {
    const dead = Object.keys(ANCHOR_WAIVED).filter((k) => {
      const [table, dim] = k.split(":") as [string, string];
      const anchors = registry.get(table);
      // 表不再注册了，或者这个维度已经补上锚点了 → 这条豁免是死的
      return !anchors || Boolean(anchors[dim]);
    });
    expect(
      dead.sort(),
      "这些豁免已经不成立（表不再注册，或该维度已经补上锚点列）—— 从 ANCHOR_WAIVED 删掉。\n"
      + "  它们各自写着一句「谁会看到空白」，而那句话现在是错的。",
    ).toEqual([]);
  });
});
