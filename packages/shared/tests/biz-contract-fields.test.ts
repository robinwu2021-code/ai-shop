// 契约声明的字段，后端必须真的下发 —— **逐个字段比，不只是比类型名**。
//
// 今天一天撞了三次同一个形状，三次都不报错：
//
//   · 核销台：契约 `Order`，后端发 `PickupOrderVO`（连 orderNo 都没有）
//   · 商品详情：契约有 `titleI18n`，后端不发 → 编辑一次译文就没了
//   · 售后：契约有 `updatedAt` / `merchantReply` / `buyerNickname`，后端发的是
//     库列名或干脆没有 → 时间显示成 NaN、商家回复整块不渲染、买家永远是「—」
//
// 前两次我只对了**类型名**，于是第三次照样漏过去。字段级才是有价值的那一层：
// TypeScript 只保证「端上自己前后一致」，它对后端发什么一无所知。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * 三端共用的实体源码，**整个目录读进来**。
 *
 * <p>`types/` 2026-09-03 从单文件（5139 行）按域拆开了。只读 `index.ts` 的话
 * 读到的是一份 `export *` 的门面 —— 一个类型都扫不到，而这道闸会**全绿**：
 * 「少扫 = 没有违规」正是它最不该有的失败方式。
 */
function sharedTypesSrc(root: string): string {
  const dir = join(root, "packages/shared/src/types");
  const src = readdirSync(dir)
    .filter((f) => f.endsWith(".ts"))
    .map((f) => readFileSync(join(dir, f), "utf8"))
    .join("\n");
  // **扫描面本身要有断言**：这道闸是「找出违规」型的，少扫一律表现为全绿。
  // 目录读空、后缀改了、路径写错 —— 都在这里当场炸，而不是安静地放行。
  const n = (src.match(/export interface \w+/g) ?? []).length;
  if (n < 100) throw new Error(`只扫到 ${n} 个实体（应有 100+）—— 扫描面塌了，闸门这时候的「绿」不作数`);
  return src;
}

const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

/**
 * 检查哪几端。
 *
 * 原先只查 `/biz`（b-app）。而同一个形状在 `/mp` 上更严重 ——
 * 求团详情的 `RequestVO` 与契约 `GroupRequest` 几乎<b>每个字段都对不上名</b>，
 * 且契约要的 `quotes` / `neighbours` 干脆没有：模板里 `request.quotes.length`
 * 读到 undefined 直接抛错，C 端的报价对比区与 B 端的整个求团池<b>一行都渲染不出来</b>。
 * 守卫只盯着一端，另一端就是盲区 —— 而两端用的是同一份契约。
 */
const APPS = [
  { app: "b-app", prefix: "/biz", fn: "m" },
  { app: "c-app", prefix: "/mp", fn: "" },
];

/**
 * 端点名 → `"METHOD 路径"`。
 *
 * **键里必须带方法。** 同一条路径上常常同时挂着 GET 与 POST
 * （`/biz/inventory/locations`：GET 列库位、POST 建仓），两者返回类型完全不同。
 * 只按路径建表的话，后解析到的那个会把前一个覆盖掉 —— 于是拿 POST 的
 * `DocNoVO` 去比 GET 的契约，报出「locationId/name/kind 都没有」这种整片的假缺失。
 * 与下面 {@link backendReturns} 里那条「同名 VO 撞包名」是同一种失效，只是换了个轴。
 */
function endpoints(app: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of read(`${app}/src/api/endpoints.ts`).matchAll(
    /(\w+):\s*\{\s*method:\s*"(GET|POST)",\s*path:\s*"([^"]+)"/g,
  )) {
    out[m[1]!] = `${m[2]!} ${m[3]!}`;
  }
  return out;
}

/** 端点名 → 契约声明的返回类型（去掉数组/分页壳） */
function declaredTypes(app: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of read(`${app}/src/api/contract.ts`).matchAll(
    /^\s{2}(\w+)\s*\([^)]*\)\s*:\s*Promise<([^;]+)>;/gm,
  )) {
    const t = m[2]!.trim()
      .replace(/^PageResult<(.*)>$/, "$1")
      .replace(/\[\]$/, "")
      .trim();
    if (/^[A-Z]\w+$/.test(t)) out[m[1]!] = t;
  }
  return out;
}

/** shared 里某个 interface 的字段：{ name, optional } */
function tsFields(type: string): { name: string; optional: boolean }[] {
  const src = sharedTypesSrc(ROOT);
  const i = src.indexOf(`export interface ${type} {`);
  if (i < 0) return [];
  const body = src.slice(i, src.indexOf("\n}", i));
  return [...body.matchAll(/^\s{2}(\w+)(\??):\s/gm)].map((m) => ({
    name: m[1]!,
    optional: m[2] === "?",
  }));
}

/** 收集后端所有 java 源码 */
function javaFiles(dir = "backend", out: string[] = []): string[] {
  for (const e of readdirSync(join(ROOT, dir))) {
    const p = `${dir}/${e}`;
    if (statSync(join(ROOT, p)).isDirectory()) javaFiles(p, out);
    else if (e.endsWith(".java") && !p.includes("/test/")) out.push(p);
  }
  return out;
}

const JAVA = javaFiles();

/**
 * 路径 → 该端点的返回类型简名与**声明它的控制器文件**。
 *
 * 记住文件是为了解析同名 record：`StaffVO` 在 merchant 与 platform 两个模块里各有一个，
 * 形状完全不同。不看来源就按第一个匹配比，会拿运营侧的 VO 去比商家侧的契约 ——
 * 报出来的缺失字段是假的，而真正缺的那些反而被这条假消息盖住。
 */
function backendReturns(): Record<string, { ret: string; file: string; pkg?: string }> {
  const out: Record<string, { ret: string; file: string; pkg?: string }> = {};
  for (const f of JAVA) {
    for (const m of read(f).matchAll(
      /@(Get|Post)Mapping\((?:value\s*=\s*)?"([^"]+)"\)[\s\S]{0,400}?public\s+([\w.<>?, ]+?)\s+\w+\s*\(/g,
    )) {
      const inner = m[3]!
        .replace(/^(java\.util\.)?(List|PageData|Page)<(.*)>$/, "$3")
        .trim();
      /*
       * **写成全限定名时，包名不能丢**。`List<ai.neargo.shop.merchant.dto.RoleVO>`
       * 剥成 `RoleVO` 之后，就和平台侧的 `PermConfigService.RoleVO` 撞了名 ——
       * 后者是运营角色（roleCode/endCode/pointCount），拿它去比商家侧的契约，
       * 会报出「perms/permLabels/usedBy 都没有」这种整片的假缺失。
       * 而假消息比没消息更糟：它把真正缺的那几条盖在噪音底下。
       */
      const dot = inner.lastIndexOf(".");
      out[`${m[1]!.toUpperCase()} ${m[2]!}`] = {
        ret: dot < 0 ? inner : inner.slice(dot + 1),
        pkg: dot < 0 ? undefined : inner.slice(0, dot),
        file: f,
      };
    }
  }
  return out;
}

/** 两条路径共同前缀的长度（按目录段算）—— 用来在同名 record 里挑「离控制器最近」的那个 */
function nearness(a: string, b: string): number {
  const x = a.split("/");
  const y = b.split("/");
  let n = 0;
  while (n < x.length && n < y.length && x[n] === y[n]) n++;
  return n;
}

/**
 * record 的组件名（含跨行签名）。找不到返回 null —— 与「找到但没有字段」要分开。
 *
 * **不能只按文件名找**。这里原先要求文件叫 `Xxx.java`，于是所有<b>嵌套 record</b>
 * 一律跳过 —— 而这个仓库里成组的 VO 恰恰都写成 `GroupVOs.java` / `TradeVOs.java`
 * 这种壳类。求团那一整块（`RequestVO` / `QuoteVO`）就落在盲区里：
 * 契约与后端<b>每个字段都对不上名</b>，两端页面都当场崩掉，而守卫一声不吭 ——
 * 它以为自己比过了，实际上一次都没比。
 * <p><b>同名 record 按控制器的 import 挑</b>：`StaffVO` 在 merchant 与 platform 里
 * 各有一个，形状完全不同（一个是店员，一个是平台运营账号）。取第一个匹配的话，
 * 会拿运营侧的 VO 去比商家侧的契约，报出一串并不存在的缺失字段 ——
 * <b>假消息比没消息更糟</b>：它把真正缺的那几条盖在噪音底下。
 *
 * <p>控制器里那行 `import ai.neargo.shop.merchant.dto.StaffVO;` 是最硬的线索，
 * 因为它就是编译器用的那条。import 找不到（同包、嵌套类）才退回按路径就近。
 */
function javaComponents(simpleName: string, from: string, pkg?: string): string[] | null {
  /*
   * **不要求 `public`**：接口成员隐式 public，所以写在 interface 里的嵌套 record
   * 源码上就是 `record InvoiceRequestVO(`。要求 `public record` 的那版把这一类
   * 整个跳过了 —— 386 个端点里有 77 个（20%）落在这个盲区，`比不了` 与 `比过了`
   * 在结果上长得一模一样，都是一片绿。
   */
  const candidates = JAVA.filter((f) => read(f).includes(`record ${simpleName}(`));
  if (!candidates.length) return null;

  // 返回类型直接写了全限定名时，它比 import 更硬 —— 那就是编译器认的那个
  const byPkg = pkg && candidates.find((c) => c.includes(pkg.replace(/\./g, "/")));

  const imported = read(from).match(
    new RegExp(`^import\\s+([\\w.]+)\\.${simpleName};`, "m"),
  )?.[1];
  const byImport = imported
    && candidates.find((c) => c.includes(imported.replace(/\./g, "/")));

  const f = byPkg ?? byImport ?? candidates.reduce((best, c) =>
    nearness(c, from) > nearness(best, from) ? c : best);
  const src = read(f);
  const i = src.indexOf(`record ${simpleName}(`);
  {
    let depth = 0;
    let j = src.indexOf("(", i);
    const start = j;
    for (; j < src.length; j++) {
      if (src[j] === "(") depth++;
      else if (src[j] === ")") {
        depth--;
        if (depth === 0) break;
      }
    }
    const body = src.slice(start + 1, j).replace(/\/\*[\s\S]*?\*\//g, "");
    // 组件形如 `String foo` / `List<X> bar` / `long baz`
    return [...body.matchAll(/[\w.<>?\[\], ]+?\s(\w+)\s*(?:,|$)/g)].map((m) => m[1]!);
  }
}

/**
 * 已知可以不一致的，必须写清为什么。
 *
 * 判据是**端上少了这个字段会怎样**：显示不出来一眼可见的（店名、图）还好，
 * 静默改变判断或整块不渲染的，不许进这张表。
 */
const EXEMPT: Record<string, string> = {
  "MerchantProfile.workability": "端上派生字段，不来自后端",
  "Order.idempotencyKey": "下单请求参数，回读时后端不必带",
  "Order.subOrders": "仅支付视角有；B 端子单视图为空数组",
};

/**
 * 端点表的键是 `"GET /mp/cart"`（带方法），而 `prefix` 是 `/mp` ——
 * **拿整个键去 `startsWith(prefix)` 永远是 false**。
 *
 * ⚠️ 这个函数是 2026-09-05 补的，补之前下面两条循环**一条端点都没比过**：
 * 键里加方法（为了区分同路径的 GET/POST）那次改动没有同步这两处判据，
 * 于是「找出违规」型的断言少扫了全部 —— 而少扫的表现是**全绿**。
 * 棘轮的注释里还写着「b-app 15 / c-app 29」，那是它还能扫的时候量的数。
 *
 * 所以 {@link scanned} 那条计数断言不是装饰：**判据自己也要有人守**。
 */
function pathOf(key: string): string {
  const i = key.indexOf(" ");
  return i < 0 ? key : key.slice(i + 1);
}

describe.each(APPS)("$app 契约字段（$prefix）", ({ app, prefix }) => {
  const eps = endpoints(app);
  const decls = declaredTypes(app);
  const rets = backendReturns();

  it("解析到足够多的端点与后端返回类型（正则失效时不要静默通过）", () => {
    expect(Object.keys(eps).length).toBeGreaterThan(50);
    expect(Object.keys(decls).length).toBeGreaterThan(30);
    expect(Object.keys(rets).length).toBeGreaterThan(50);
  });

  /**
   * **可选字段也要有人守**（2026-09-05 加）。
   *
   * 上面那条只判**必填**字段：可选的少一个不会让端上崩，所以按设计跳过。
   * 代价在 `Order` 上现了形 —— `reviewed` / `redeemCode` / `afterSale` /
   * `payGroupNo` 四个可选字段端上声明了、后端一个都不发，而后果全是**静默的**：
   *
   *   · 已评价的订单照样显示「去评价」
   *   · 兑换码那张卡永远不出现，码落进「取货码」卡被标错
   *   · 「售后进行中」整张卡与它的两个动作永远不出现
   *   · 拆单提示永远不出现
   *
   * 而 **mock 四个都有**，所以本机点一遍全都对，只有真机是哑的
   *（见 TDD-交互清单缺口修复 G15）。
   *
   * 所以这一条不要求清零 —— 「有意的子集」确实存在（`OrderPreview` 的类型注释
   * 写着它只声明预览页要用的那部分）—— 而是**只许降不许升**：
   * 再新增一个「声明了但拿不到」的可选字段，闸门当场变红。
   */
  it("★★ 契约声明了、后端 VO 没有的**可选**字段（棘轮：只许降不许升）", { timeout: 30_000 }, () => {
    const missing = new Set<string>();
    let scanned = 0;

    for (const [name, path] of Object.entries(decls)) {
      const url = (eps[name] ?? "").replace(/:(\w+)/g, "{$1}");
      if (!pathOf(url).startsWith(prefix)) continue;
      const hit = rets[url];
      if (!hit) continue;
      const comps = javaComponents(hit.ret, hit.file, hit.pkg);
      if (!comps) continue;
      const fields = tsFields(path);
      if (!fields.length) continue;
      scanned += 1;
      for (const f of fields) {
        if (!f.optional || comps.includes(f.name) || EXEMPT[`${path}.${f.name}`]) continue;
        missing.add(`${path}.${f.name}（后端 ${hit.ret} 没有）`);
      }
    }

    expect(scanned, `${app}：一条端点都没比过 —— 判据失效了，不是真的没问题`)
      .toBeGreaterThan(10);

    /*
     * 基线 2026-09-05 实测。修一条减一。
     *
     * ⚠️ **减到 0 之前别把它当噪声**：这里每一条的形状都是
     * 「端上写了一段只在 mock 下成立的逻辑」，而那类缺陷在真机上不报错、
     * 只是那块界面永远不出现。
     */
    const OPTIONAL_BASELINE: Record<string, number> = { "b-app": 9, "c-app": 12 };
    expect(
      missing.size,
      `${app}：契约声明了、后端不发的可选字段共 ${missing.size} 个`
        + `（基线 ${OPTIONAL_BASELINE[app]}）——\n`
        + "  端上照着它写的那段界面在真机上永远不出现，而本机（mock）是对的。\n  "
        + [...missing].join("\n  "),
    ).toBeLessThanOrEqual(OPTIONAL_BASELINE[app] ?? 0);
  });

  /*
   * ⚠️ 这两条要显式给 30 秒。它们逐个契约方法去后端源码里找返回类型并解析字段，
   * 单独跑 3 秒出头 —— 但全量是并发跑的，几十个文件抢 CPU 时轻易越过默认的 5 秒。
   *
   * 表现是**偶发红**：同样的代码，单独跑绿、全量跑红，而失败信息是
   * 「Test timed out」，与被测的内容毫无关系。这类闸门比没有闸门更糟 ——
   * 它会训练所有人把它的红当噪声，而真的漏了字段那次也一样被忽略。
   */
  it("★★★ 契约里的必填字段，后端 VO 必须真的有 —— 少一个就是屏幕上静默少一块", { timeout: 30_000 }, () => {
    const offenders: string[] = [];
    let scanned = 0;

    for (const [name, path] of Object.entries(decls)) {
      const url = (eps[name] ?? "").replace(/:(\w+)/g, "{$1}");
      if (!pathOf(url).startsWith(prefix)) continue;
      const hit = rets[url];
      if (!hit) continue; // 后端路径没解析到：另一条守卫的事
      const { ret, file, pkg } = hit;
      const comps = javaComponents(ret, file, pkg);
      if (!comps) continue; // 不是 record（Map/void/未知）——比不了就别假装比过
      scanned += 1;

      const missing = tsFields(path)
        .filter((f) => !f.optional)
        .map((f) => f.name)
        .filter((f) => !comps.includes(f))
        .filter((f) => !EXEMPT[`${path}.${f}`]);

      if (missing.length) {
        offenders.push(`${name}（${url}）契约 ${path} 要 ${missing.join("/")}，而 ${ret} 没有`);
      }
    }

    /*
     * 存量欠账。**按端分开记**——合成一个数的话，一端修好了另一端就白得一个名额。
     *
     * 2026-08-13 清零过一次：修好「只按文件名找 record」那个盲区时是 21 处
     * （/biz 7、/mp 14），数字那么大不是因为一夜之间坏了这么多，而是守卫此前
     * <b>根本没在比</b> —— 这个仓库的 VO 大多嵌在 `XxxVOs.java` 里，于是求团、
     * 商家团、优惠券、门店主页整块整块地跳过。
     *
     * 2026-08-14 又修一层盲区（要求 `public record`，漏掉 interface 里的隐式
     * public 嵌套 record，386 个端点里 77 个受影响），翻出 c-app 这一条：
     *
     *   payOrder 契约声明 `Promise<Order>`，而后端返回的是
     *   `PayResult(orderNo, payChannel, payParams)` —— 支付参数，不是订单。
     *
     * 今天不炸，因为 `pages/pay/index.vue` 拿到返回值就扔了（注释写着「以回查为准」）。
     * 但那一行的上面还写着「真实链路：后端下单拿支付参数 → 唤起」——
     * <b>真接微信支付的人会发现 `res.payParams` 在类型里不存在</b>，
     * 而最省事的解法（`as any`）会把这个错永久固化。
     *
     * 没有在这次一并修：`c-app/src/api/contract.ts` 此刻有并行会话未提交的推送契约，
     * 改它就没法只提交自己那部分。修法是加一个 `PayParams` 类型（对应 PayResult）
     * 并同步 mock，然后把这里改回 0。
     *
     * 这个数只许降不许升：**新增一处立刻变红**。
     */
    const PENDING: Record<string, number> = { "b-app": 0, "c-app": 1 };
    const budget = PENDING[app] ?? 0;
    if (offenders.length && offenders.length <= budget) {
      expect(
        offenders.length,
        `${app} 的存量欠账应当只减不增（当前 ${offenders.length}，记录值 ${budget}）——\n`
          + "  修掉一处就把 PENDING 减一。\n  " + offenders.join("\n  "),
      ).toBe(budget);
      return;
    }

    expect(scanned, `${app}：一条端点都没比过 —— 判据失效了，不是真的没问题`)
      .toBeGreaterThan(10);
    expect(
      offenders,
      "契约声明了、后端 VO 里没有的字段 ——\n"
        + "  端上照契约写，屏幕上就静默少一块：时间显示成 NaN、整块 v-if 不渲染、\n"
        + "  或者永远是「—」。少的若是数组（quotes/neighbours），页面直接抛错整页白。\n"
        + "  TypeScript 挡不住这类：它只保证端上自己前后一致。\n"
        + "  修：后端补字段（多数情况），或改契约并同步改页面。\n  "
        + offenders.join("\n  "),
    ).toEqual([]);
  });

  /*
   * **反方向**：后端发了、契约没声明 —— 数据到了端上被静默丢掉。
   *
   * 上一条查的是「契约要的后端没有」（屏幕上少一块）。这一条是它的镜像，
   * 2026-08-17 靠人工测试才发现：
   *
   *   后端 `CartItemVO` 一直在发 `merchantNo` / `merchantName`，
   *   而 shared 的 `CartItem` 没声明这两个字段。于是购物车只能按履约方式分组，
   *   **店名一个字都显示不出来** —— 用户从头到尾看到「一单」，
   *   提交后拿到按商家拆出的两笔子订单。
   *
   * 为什么上一条抓不到：它只从契约往后端看。契约里压根没有的字段，
   * 在那个方向上不存在，自然也就「没缺」。
   *
   * 为什么用棘轮而不是要求清零：后端 VO 里有大量端上确实不需要的字段
   * （tenantNo、审计列、内部编号），全要求声明是噪音。这个数只保证
   * **新丢掉的字段立刻变红**，存量慢慢还。
   */
  it("★★ 后端发了而契约没声明的字段（棘轮：只许降不许升）", { timeout: 30_000 }, () => {
    /*
     * **按「类型.字段」去重计数，不按端点条目**。
     *
     * 第一版按条目计：`Order` 在 6 个端点上各报一条，修好其中一个字段
     * （`subOrders`）之后条目一条都没少 —— 因为那几条还欠着别的字段。
     * 于是「修了一个真问题」和「什么都没干」在数字上一模一样，
     * 而一个看不出进展的棘轮，下一个人就不会再去拧它。
     */
    const dropped = new Set<string>();
    let scanned = 0;

    for (const [name, path] of Object.entries(decls)) {
      const url = (eps[name] ?? "").replace(/:(\w+)/g, "{$1}");
      if (!pathOf(url).startsWith(prefix)) continue;
      const hit = rets[url];
      if (!hit) continue;
      const comps = javaComponents(hit.ret, hit.file, hit.pkg);
      if (!comps) continue;

      const declared = new Set(tsFields(path).map((f) => f.name));
      if (!declared.size) continue; // 契约类型解析不到，别假装比过
      scanned += 1;
      for (const c of comps) {
        if (declared.has(c) || IGNORED_BACKEND_FIELDS.has(c)) continue;
        dropped.add(`${path}.${c}（后端 ${hit.ret}）`);
      }
    }

    /*
     * **先断言真的比过东西。** 少扫的表现是全绿 —— 这条棘轮就这么空转过一段时间：
     * 端点表的键改成带方法（`"GET /mp/cart"`）之后，`startsWith("/mp")` 永远为假，
     * 于是一条端点都没进循环，而断言照绿。见 {@link pathOf} 那段注释。
     */
    expect(scanned, `${app}：一条端点都没比过 —— 判据失效了，不是真的没问题`)
      .toBeGreaterThan(10);

    /*
     * 存量欠账。**这批不是全都无害**，挑出来的几条记在这里，
     * 免得它们混在数字里没人再看：
     *
     *   · ~~`Order` 丢 `subOrders`~~ —— **2026-08-17 已修**。CartItem 那条的同族。
     *   · ~~`CartItem` 的 `invalid` / `available`~~ —— **2026-09-05 已修**。
     *     它此前被记成「同物异名而非真丢」（端上叫 `invalidReason`）并判为不在范围里，
     *     而实际后果是：**后端标的失效标记一个字节都到不了端上**，
     *     已下架的商品在购物车里完全正常、能勾能结算，一直到下单那一刻才被拒。
     *     见 TDD-购物车与下单优化 §1 缺陷 D。
     *   · `CartItem.selected` —— 后端有这一列也在发，但**没有任何端点能写它**；
     *     端上这次的勾选留在本地（TDD §3.6.1），接通是一件独立的事。
     *   · `StoreHome` 丢 `closed` —— 店铺打烊与否端上不知道，会让人对着打烊的店下单
     *   · `Goods` 丢 `auditReason` —— 商品被驳回，商家看不到理由（b-app 侧同样丢）
     *   · `MerchantApplyStatus` 丢 `qualificationItems` —— 入驻要补哪张证，端上说不出
     *   · `SettleBill` 丢 `businessMode/invoiceStatus/paymentRef/pointsFeeMinor`
     *   · `FrequentItem.available` —— 与 CartItem 那条同族，常买列表不知道货还有没有
     *
     * `OrderPreview` 那 20 条是**有意的子集**（它的类型注释写着理由：声明全套会让
     * 后端每加一个字段都得改端上类型），与真漏接混在一个数里是这个棘轮的已知钝处。
     *
     * 这个数只许降不许升。修一条减一。
     *
     * ⚠️ **基线是 2026-09-05 重新量的**（b-app 31 / c-app 25）。
     * 旧值 15 / 29 是判据还能扫的时候量的，中间空转了一段，期间新欠的账没人拦住 ——
     * 这也正是上面那条 `scanned` 断言存在的理由。
     *
     * ⚠️ **c-app 当天晚些时候从 25 抬到 28，抬的不是欠账**：`OrderVO` 补了
     * `reviewed` / `afterSale` / `payGroupSize` 三个字段（G15），端上三个都接了，
     * 而 `OrderPreview` 是 `OrderVO` 的**有意子集**，于是它一口气多欠三条。
     * 这正是上面说的那个钝处 —— 真要把两类分开，得给子集类型加个标记，
     * 那是这条棘轮自己的下一步。
     */
    const BASELINE: Record<string, number> = { "b-app": 31, "c-app": 28 };
    expect(
      dropped.size,
      `${app}：后端在发、契约没接的字段共 ${dropped.size} 个（基线 ${BASELINE[app]}）——\n`
        + "  这类不会报错，只是数据到端上就没了。修：契约补字段，或确认端上真的不需要\n"
        + "  （真不需要就加进 IGNORED_BACKEND_FIELDS 并写清理由）。\n  "
        + [...dropped].join("\n  "),
    ).toBeLessThanOrEqual(BASELINE[app] ?? 0);
  });
});

/**
 * 端上确实不需要的后端字段。**加进来要说得出为什么端上不需要**，
 * 否则这张表会退化成「报错了就加一行」，守卫就此失效。
 */
const IGNORED_BACKEND_FIELDS = new Set([
  "tenantNo", "createdBy", "updatedBy", "version", "deleted",
  "id", // 库自增主键，端上一律用业务单号
]);
