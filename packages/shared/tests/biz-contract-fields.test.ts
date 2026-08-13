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

/** 端点名 → 路径 */
function endpoints(app: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of read(`${app}/src/api/endpoints.ts`).matchAll(
    /(\w+):\s*\{\s*method:\s*"(?:GET|POST)",\s*path:\s*"([^"]+)"/g,
  )) {
    out[m[1]!] = m[2]!;
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
  const src = read("packages/shared/src/types/index.ts");
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
function backendReturns(): Record<string, { ret: string; file: string }> {
  const out: Record<string, { ret: string; file: string }> = {};
  for (const f of JAVA) {
    for (const m of read(f).matchAll(
      /@(?:Get|Post)Mapping\((?:value\s*=\s*)?"([^"]+)"\)[\s\S]{0,400}?public\s+([\w.<>?, ]+?)\s+\w+\s*\(/g,
    )) {
      const ret = m[2]!
        .replace(/^(java\.util\.)?(List|PageData|Page)<(.*)>$/, "$3")
        .replace(/^[\w.]*\./, "")
        .trim();
      out[m[1]!] = { ret, file: f };
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
function javaComponents(simpleName: string, from: string): string[] | null {
  const candidates = JAVA.filter((f) => read(f).includes(`public record ${simpleName}(`));
  if (!candidates.length) return null;

  const imported = read(from).match(
    new RegExp(`^import\\s+([\\w.]+)\\.${simpleName};`, "m"),
  )?.[1];
  const byImport = imported
    && candidates.find((c) => c.includes(imported.replace(/\./g, "/")));

  const f = byImport ?? candidates.reduce((best, c) =>
    nearness(c, from) > nearness(best, from) ? c : best);
  const src = read(f);
  const i = src.indexOf(`public record ${simpleName}(`);
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

describe.each(APPS)("$app 契约字段（$prefix）", ({ app, prefix }) => {
  const eps = endpoints(app);
  const decls = declaredTypes(app);
  const rets = backendReturns();

  it("解析到足够多的端点与后端返回类型（正则失效时不要静默通过）", () => {
    expect(Object.keys(eps).length).toBeGreaterThan(50);
    expect(Object.keys(decls).length).toBeGreaterThan(30);
    expect(Object.keys(rets).length).toBeGreaterThan(50);
  });

  it("★★★ 契约里的必填字段，后端 VO 必须真的有 —— 少一个就是屏幕上静默少一块", () => {
    const offenders: string[] = [];

    for (const [name, path] of Object.entries(decls)) {
      const url = (eps[name] ?? "").replace(/:(\w+)/g, "{$1}");
      if (!url.startsWith(prefix)) continue;
      const hit = rets[url];
      if (!hit) continue; // 后端路径没解析到：另一条守卫的事
      const { ret, file } = hit;
      const comps = javaComponents(ret, file);
      if (!comps) continue; // 不是 record（Map/void/未知）——比不了就别假装比过

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
     * 存量欠账：**修好盲区那一刻**库里就有的 21 处（/biz 7、/mp 14），
     * 修掉求团、商家团、优惠券、门店主页与常买清单，再摘掉 4 条同名 record 撞出来的假消息之后，剩 6 处 ——
     * **全在 /mp**，/biz 这一侧已经清零。
     *
     * 数字这么大不是因为一夜之间坏了这么多，而是因为守卫此前<b>根本没在比</b>——
     * 它只按文件名找 record，而这个仓库的 VO 大多嵌在 `XxxVOs.java` 里，
     * 于是整块整块地跳过（求团、商家团、优惠券、门店主页…）。
     *
     * 它们都是**真缺陷**，不是可豁免项 —— 商家团缺 `merchant`/`members`/`expireAt` 九个字段，
     * 优惠券列表拿不到券名与面额，门店主页拿不到店与货。所以不进 EXEMPT
     * （那张表是「少了也没关系，且写清为什么」），而记成一个<b>只许降不许升</b>的数。
     *
     * 这样两件事同时成立：今天不必一次修完，而**明天新增一处立刻变红**。
     * 修掉一处就把数字减一 —— 数字不允许往上走，这是它唯一的价值。
     */
    const PENDING = { "b-app": 0, "c-app": 6 }[app] ?? 0;
    if (offenders.length && offenders.length <= PENDING) {
      expect(
        offenders.length,
        `${app} 的存量欠账应当只减不增（当前 ${offenders.length}，记录值 ${PENDING}）——\n`
          + "  修掉一处就把 PENDING 减一。\n  " + offenders.join("\n  "),
      ).toBe(PENDING);
      return;
    }

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
});
