// 库存首页那九个入口**摆在哪一层**。
//
// 分层的判据只有一个：**多久用一次**。
//   · 进货、报损     几乎每天    ┐ 贴底那条的四枚按钮
//   · 盘点、调拨     一周到一月  ┘
//   · 单据、报表     要查的时候  ┐ 「更多」展开的那段菜单里
//   · 库位、供应商   建好就不动  ┘
//
// **2026-09-17 从两枚扩到四枚**（商家定的）：两枚时每枚 136px，而内容只需 73px，
// 整条横在那儿没吃满。四枚时每枚 64px —— 按钮的左右内边距同时从 28rpx 收到 16rpx，
// 内容降到 61px，`＋/－` 两个图标才保得住（那是「进货是加、报损是减」那一眼）。
//
// **将来按真实使用频次换人**：现在的顺位是拍出来的（「一周到一月」是估的），
// 等埋点攒够数据，如果报表比盘点还常用，就把报表换上去 —— 这一层的形状不用动，
// 只改下面两个数组的成员。
//   · 看各店的货     它不是一件要办的事，是把上面那四个数按门店再拆一遍 → 贴在数字下面
//
// 此前九条分两处摆：四个写动作在贴底条，另外五个在总览卡里排成一行**等大的文字链接**。
// 那一行是散的根源 —— 五个入口在语义上是三类东西（查记录 / 看各店 / 一次性设置），
// 长得却完全一样，而且它们一个都不够格常年占着「每天要看几十遍」的那一屏。
//
// **菜单里只有名字，没有分组标题也没有一句说明**（2026-09-17 商家定的）：
// 六个名字是这一行通用的说法，不是黑话；给每条配一句解释反而像在教人认字。
// 顺序仍按上面那三档排，只是不把档名写出来。
//
// **照 `stock-urgent.ts` 的形状写**：这一层只回答「有哪些、能不能点」，
// 不回答「长什么样」，也不碰 i18n —— 于是它能在 node 里直接断言，
// 而 b-app 的测试今天只跑得动纯函数（见 `vitest.config.mts` 的头注）。
import { ROUTES } from "./nav";

export interface StockEntry {
  key: string;
  route: string;
  /**
   * 这一条现在用不了。**要给原因，也要尽量给去处** ——
   * 只是灰掉的按钮会让人以为是坏了，而不是「还差一步」。
   *
   * 它与上面说的「不写说明」不冲突：说明是常驻的解释，这一句只在真用不了时出现。
   */
  blocked?: {
    /** 词条 key，翻译交给调用方 */
    reasonKey: string;
    params?: Record<string, number>;
    /** 去把它补上的那一页。**没有权限去补的人不给这个去处** */
    route?: string;
  };
}

export interface StockEntriesCtx {
  can: (perm: string) => boolean;
  /**
   * 现在开得了新盘点单吗（`canStartNewCount()` 的结果）。
   * **开不了就不摆「盘点」这个入口** —— 有单开着时，那一格的事由「继续盘点」接着，
   * 两个都摆出来的下场是开出第二张，而两张单锁的账面数是两个时刻的。
   */
  canStartCount: boolean;
  /** 开了不止一家店。**数的是门店不是库位** */
  multiStore: boolean;
  /**
   * 除在途外能放货的地方有几个。
   * **还没取到时给 `null`** —— 「不知道」不等于「不足两个」：
   * 当成 0 的话，页面一打开调拨就是灰的，等数回来又变回可点。
   */
  usableLocations: number | null;
}

/** 调拨至少要两个放货的地方，一个的话「调到哪儿」根本选不出东西 */
const TRANSFER_MIN_LOCATIONS = 2;

/** 这一层内部用：比 `StockEntry` 多一个权限码，出口处脱掉 */
interface Def {
  key: string;
  route: string;
  perm: string;
}

function strip(d: Def): StockEntry {
  return { key: d.key, route: d.route };
}

/**
 * 每一条按**它自己那一页的权限**判，不是按库存页的：
 * 报表要 `biz:customer`、库位要 `biz:store:admin`。
 * 按本页判的话，店员会看到一道点进去就是「这页不该你看」的门 —— 那比没有门更让人困惑。
 */
export function stockEntries(ctx: StockEntriesCtx): {
  primary: StockEntry[];
  more: StockEntry[];
  cross: StockEntry | null;
} {
  const { can, multiStore, usableLocations } = ctx;

  /*
   * ★ **用不了的不往上提。**
   *
   * 贴底条上放不下一句解释：抽屉里「调拨」被拦时能就地写
   * 「要两个放货的地方，现在只有 1 个」，条上就只剩一枚灰按钮 ——
   * 而这个文件自己写着「只是灰掉的按钮会让人以为是坏了」。
   *
   * 盘点同理：有单开着时它整条不出现（见下面 more 里那段），自然也提不上来。
   *
   * 于是三种退化路径都不需要第二套布局：
   *   盘点没了 → 三枚　·　调拨被拦 → 三枚　·　两个都没 → 两枚（就是扩之前那个样子）
   */
  const transferBlocked =
    usableLocations !== null && usableLocations < TRANSFER_MIN_LOCATIONS;

  const primary: StockEntry[] = (
    [
      { key: "purchase", route: ROUTES.purchaseEdit, perm: "biz:stock" },
      { key: "out", route: ROUTES.stockOut, perm: "biz:stock" },
      ...(ctx.canStartCount
        ? [{ key: "check", route: ROUTES.stockCheck, perm: "biz:stock" }] : []),
      ...(transferBlocked
        ? [] : [{ key: "transfer", route: ROUTES.transfer, perm: "biz:stock" }]),
    ] satisfies Def[]
  )
    .filter((e) => can(e.perm))
    .map(strip);

  /*
   * 顺序按「多久用一次」从高到低：单据 / 报表（要查的时候）
   * → 库位 / 供应商（建好就不动）。**不写分组标题** —— 见文件头。
   */
  const more: StockEntry[] = (
    [
      // 调拨被拦时**回到抽屉里**：这儿放得下那句「要两个放货的地方，现在只有 1 个」，
      // 条上放不下。有单开着时盘点整条不出现 —— 不是灰掉：这不是「你缺个什么」，
      // 而是「这件事正由工作台那条『继续盘点』接着」，摆个灰名字只会让人问为什么
      ...(transferBlocked
        ? [{ key: "transfer", route: ROUTES.transfer, perm: "biz:stock" }] : []),
      { key: "docs", route: ROUTES.stockDocs, perm: "biz:stock" },
      { key: "report", route: ROUTES.stockReport, perm: "biz:customer" },
      { key: "locations", route: ROUTES.locations, perm: "biz:store:admin" },
      // 供应商与进货同一个码：能记进货的人就该能建供应商
      { key: "suppliers", route: ROUTES.suppliers, perm: "biz:stock" },
      // 哪些品类记库存（TDD-商品纳入进销存开关）。改它就是决定一件商品走哪本账，
      // 与改价、上下架同一级 —— 用商品的码，不用库存的码
      { key: "settings", route: ROUTES.stockSettings, perm: "biz:goods" },
      // 跨店总览收进抽屉（2026-09-17 店主要求「顶部简洁一点」）。
      // **不是删掉** —— 它是那一页的唯一入口，删了就再也进不去。
      // 只给多门店：单店商家的「跨店」就是这一页本身
      ...(multiStore ? [{ key: "cross", route: ROUTES.stockCross, perm: "biz:stock" }] : []),
    ] satisfies Def[]
  )
    .filter((e) => can(e.perm))
    .map((e) => (e.key === "transfer" ? withTransferBlock(strip(e), ctx) : strip(e)));

  /*
   * 跨店总览（INV-S7）。**只给多门店商家** —— 单店商家的「跨店」就是这一页本身。
   *
   * 判据用 `multiStore` 而不是 `crossStoreStats` 那个付费能力位：分层整体还没做，
   * 单独给这一个功能加门槛会变成「只有跨店总览要钱」。等分层做的时候一起接。
   */
  const cross =
    multiStore && can("biz:stock")
      ? { key: "cross", route: ROUTES.stockCross }
      : null;

  return { primary, more, cross };

  function withTransferBlock(e: StockEntry, c: StockEntriesCtx): StockEntry {
    if (c.usableLocations === null || c.usableLocations >= TRANSFER_MIN_LOCATIONS) return e;
    return {
      ...e,
      blocked: {
        reasonKey: "stock.entryBlocked.transfer",
        params: { n: c.usableLocations },
        // 去添加的那一页要 `biz:store:admin`。**没有这个码的人不给去处** ——
        // 给了等于把他送到一道「这页不该你看」的门前，而他并不知道自己做不了
        route: c.can("biz:store:admin") ? ROUTES.locations : undefined,
      },
    };
  }
}

/** 放货的地方有几个：在途不算 —— 那批货既不在 A 也不在 B，调拨挑不到它 */
export function countUsableLocations(locs: { kind?: string }[]): number {
  return locs.filter((l) => l.kind !== "TRANSIT").length;
}
