// b-app 的**页面门禁**必须覆盖该页实际调用的权限码。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么后端那四条守卫看不见这件事
// ─────────────────────────────────────────────────────────────────────────────
// `BizEndpointPermTest` 证明的是「每个端点都登记了权限、且注解真的在」——
// 那是**后端拒绝得对不对**。而这条守卫问的是另一个问题：
// **前端有没有在把用户送进一个他注定打不通的页面。**
//
// 两者的失败长得完全不一样：
//   · 后端漏判 → 越权，没人报错
//   · 前端漏裁 → 后端正确返回 70006，而页面把它渲染成「这家店什么都没有」
//
// 2026-08-12 的盘点里，六个角色有三个用不了为他们设计的页面：
// 配送员打开配送页整页空（规则接口要 biz:store，他只有 biz:ship，
// 而裸 Promise.all 让待送列表跟着一起 reject）、理货员打开分拣页整页空、
// 客服点「商品」tab 吃一个 toast。**四百多条后端测试全绿。**
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么用「豁免清单」而不是基线快照
// ─────────────────────────────────────────────────────────────────────────────
// 快照式（ops-perm-matrix 那种）零维护，但它把变化摊成一个 diff，
// 不强迫任何人回答「这一页凭什么可以不判」。而这里恰恰有**两种合法的门禁不足**，
// 必须逐条说清楚是哪一种：
//   ① 页内逐块裁（工作台每个格子跟着自己的 perm）
//   ② 调用点自己 catch 且 UI 判空（配送页的规则卡）
// 所以豁免要写理由，**理由为空就红**。
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error 生成器是 .mjs，没有类型声明；这里只用它的纯解析结果
import { pages, clashes, ROLE_CN } from "../../../scripts/gen-biz-feature-perm-matrix.mjs";

const PAGES = join(import.meta.dirname, "../../../b-app/src/pages");

const sourceOf = (dir: string) =>
  readdirSync(join(PAGES, dir))
    .map((f) => readFileSync(join(PAGES, dir, f), "utf8"))
    .join("\n");

/**
 * 这一页是不是**真的按这个码裁了一块**。
 *
 * 只认两种写法：`can('biz:xxx')` 与 `perm: 'biz:xxx'`（TABS/格子那种表驱动的）。
 * **不能只搜码本身** —— 每一处裁剪旁边都有一段注释解释它为什么在，
 * 而注释里同样写着那个码：把裁剪删掉、注释留下，裸搜字符串照样绿。
 * 第一版就是这么写的，一次模拟回退把它戳穿了。
 */
const cutsBy = (src: string, code: string) =>
  new RegExp(`can\\(\\s*["']${code}["']\\s*\\)|perm:\\s*["']${code}["']`).test(src);

/**
 * 允许「门禁没覆盖到」的页面 × 权限码，**每条都要写为什么**。
 *
 * key 是 `页面/权限码`。理由要说清楚缺的那一块在页面上如何处理 ——
 * 「因为它是对的」不算理由。
 */
const EXEMPT: Record<string, string> = {
  // ① 页内逐块裁
  "home/biz:customer":
    "工作台每个格子跟着自己的 perm（cells 按 c.perm 过滤），经营数据卡按 can() 判；调用前也先 can()",
  "home/biz:finance":
    "「还不能收数」这张 blocker 卡只给能处理它的人看，调用前先 can('biz:finance')",
  "home/biz:store":
    "「没选社区」这张 blocker 卡同上，调用前先 can('biz:store')",
  "goods-list/biz:store":
    "「本店类目」筛选那一段的 mStoreCategories 用 can('biz:store') 包住并单独 catch —— "
    + "店员与理货员进得来这一页（门禁是 biz:stock），但货架不是他们的事，那一段对他们不画",
  "goods-list/biz:goods":
    "列表本身要 biz:stock（已是门禁）；新建/编辑/上下架三个按钮各自 v-if=can('biz:goods')",
  "order/biz:ship":
    "详情页门禁是 biz:order:view；发货/送达按钮 canShip/canDeliver 里带 can('biz:ship')",
  "orders/biz:aftersale":
    "「售后」tab 按 perm 从 TABS 里过滤掉，店员与配送员看不到它，也就不会去调 mAfterSaleList",

  // ② 调用点自己 catch + UI 判空
  "delivery/biz:store":
    "规则卡片用 can('biz:store') 包住并单独 catch；配送员只拿待送列表与「已送达」，两个码他都有",
  "picking/biz:verify":
    "自提单调用用 can('biz:verify') 包住并单独 catch；理货员看到的是分拣单与短少上报，到货区不画",
  "me/biz:store:admin":
    "「我的套餐」这一行（含 mMyPlan）用 can('biz:store:admin') 包住并单独 catch，"
    + "且 loadPlan() 里先 await ensureScope() —— 否则深链进来 can() fail-closed，老板永远看不到这一行。"
    + "店长看不到套餐是刻意的：他不决定要不要升档",
};

interface Page {
  dir: string;
  gates: string[];
}
interface Clash {
  role: string;
  missing: string[];
}

describe("B 端页面门禁", () => {
  it("★★★ 页面门禁必须覆盖该页调用所需的权限码 —— 覆盖不到的必须写明为什么", () => {
    const unexplained: string[] = [];
    for (const p of pages as Page[]) {
      for (const c of clashes(p) as Clash[]) {
        for (const code of c.missing) {
          const key = `${p.dir}/${code}`;
          if (!EXEMPT[key]?.trim()) {
            unexplained.push(
              `${key}　—— ${ROLE_CN[c.role] ?? c.role}进得了这一页，但页面里有他打不通的请求`,
            );
          }
        }
      }
    }
    expect(
      unexplained,
      "这些页面会把 70006 变成「整页空白」或一个点不动的按钮。\n" +
        "两条路二选一：\n" +
        "  ① 给页面加门禁（:denied=\"!merchant.can('...')\"）—— 整页都要这个码时用\n" +
        "  ② 页内按 can() 裁那一块 + 调用点单独 catch，然后把这条加进 EXEMPT 并写清楚\n" +
        "⚠️ 用 can() 决定**发不发请求**时，load() 开头要先 await merchant.ensureScope()：\n" +
        "   can() 在权限没加载时 fail-closed 返回 false，深链进来就永远不发，且不会重试。\n" +
        "未说明的有：\n  " + unexplained.join("\n  "),
    ).toEqual([]);
  });

  /*
   * 上一条只问「有没有人想过」，这一条问「那个说法还算不算数」。
   *
   * 理由是人写的散文，机器读不懂 —— 但**所有合法的处理方式都会在源码里留下同一种痕迹**：
   * `can('biz:xxx')` 或表驱动的 `perm: 'biz:xxx'`。这处调用没了，说明那段裁剪没了，
   * 而豁免还留着。
   *
   * ⚠️ **这是弱检查，要知道它看不见什么**：它证明不了「catch 还在」，
   * 也证明不了 can() 包的是正确的那个调用。真正的回归防线是六角色手动走查。
   * 但它能拦住最常见的一种：**重构时把那段裁剪删了，豁免忘了跟着删。**
   */
  it("★★ 豁免说的那处裁剪必须还在 —— 页面源码里必须仍出现这个码", () => {
    const vanished: string[] = [];
    for (const [key, reason] of Object.entries(EXEMPT)) {
      const [dir, ...rest] = key.split("/");
      const code = rest.join("/");
      if (!cutsBy(sourceOf(dir!), code)) {
        vanished.push(`${key}　豁免理由说：${reason}`);
      }
    }
    expect(
      vanished,
      "豁免声称页面自己裁了这一块，但页面源码里已经找不到这个权限码了 ——\n" +
        "要么裁剪被删了（那这一页现在会把 70006 渲染成空白），\n" +
        "要么它真的不需要了（那把豁免一起删掉）：\n  " + vanished.join("\n  "),
    ).toEqual([]);
  });

  it("★★ 豁免清单里不能留已经不成立的条目 —— 名单本身也会过期", () => {
    const live = new Set<string>();
    for (const p of pages as Page[]) {
      for (const c of clashes(p) as Clash[]) {
        for (const code of c.missing) live.add(`${p.dir}/${code}`);
      }
    }
    const stale = Object.keys(EXEMPT).filter((k) => !live.has(k));
    expect(
      stale,
      "这些豁免已经不需要了（页面加了门禁，或那个调用没了）。\n" +
        "留着会让人以为某一页仍然是「特殊情况」，而它早就正常了：\n  " + stale.join("\n  "),
    ).toEqual([]);
  });

  it("★ 每个页面都至少有一条真实来源 —— 扫不到页面说明正则或路径变了", () => {
    expect((pages as Page[]).length).toBeGreaterThan(15);
    expect((pages as Page[]).filter((p) => p.gates.length).length).toBeGreaterThan(10);
  });
});
