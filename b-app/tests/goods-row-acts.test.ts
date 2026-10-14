import { describe, expect, it } from "vitest";

/**
 * 商品行上「上架 / 下架」这个按钮，判据必须是**门店级**状态。
 *
 * <p>主体级的 `onSale` 是「任一门店在售就为真」的总闸。多门店时店长在自己店里
 * 下架完，主体级仍是 true —— 状态标（读门店级）已经写「已下架」，
 * 按钮（读主体级）却还写「下架」。他再点一次等于又把这家店开回去，
 * 而两次点击看起来做的是同一件事。
 *
 * <p>2026-09-16 线上实测撞到：默认店 `on_sale=0`、分店 `1`、主体 `1`。
 * 店主的原话是「商品列表页还是错误」。
 *
 * <p>这里复刻页面里的那两个函数（`stateOf` / 按钮取向），
 * 而不是把整个 SFC 挂起来跑 —— 要钉的是这条判据，不是渲染。
 */
type Row = {
  status?: "PENDING" | "REJECTED" | "ON_SALE" | "OFF_SALE";
  onSale: boolean;
  storeOnSale?: boolean | null;
};

/** 与 pages/goods-list/index.vue 的 stateOf 同一段逻辑 */
function stateOf(g: Row) {
  const base = g.status ?? (g.onSale ? "ON_SALE" : "OFF_SALE");
  if (base === "PENDING" || base === "REJECTED") return base;
  if (g.storeOnSale == null) return base;
  return g.storeOnSale ? "ON_SALE" : "OFF_SALE";
}

/** 按钮该写哪一个 */
function toggleLabel(g: Row) {
  return stateOf(g) === "ON_SALE" ? "offSale" : "onSale";
}

describe("上下架按钮读门店级，不读主体级", () => {
  it("★★★ 本店已下架、主体仍在售 → 按钮必须是「上架」", () => {
    // 线上那一行：默认店 0、分店 1、主体 1
    const row: Row = { status: "ON_SALE", onSale: true, storeOnSale: false };
    expect(stateOf(row)).toBe("OFF_SALE");
    // 读主体级的话这里会是 offSale —— 那正是店主看到的自相矛盾
    expect(toggleLabel(row)).toBe("onSale");
  });

  it("★★ 本店在售 → 按钮是「下架」", () => {
    expect(toggleLabel({ status: "ON_SALE", onSale: true, storeOnSale: true })).toBe("offSale");
  });

  it("★★ 单店（storeOnSale 为空）跟随主体级 —— 行为与多门店改造前逐字相同", () => {
    expect(toggleLabel({ status: "ON_SALE", onSale: true, storeOnSale: null })).toBe("offSale");
    expect(toggleLabel({ status: "OFF_SALE", onSale: false, storeOnSale: null })).toBe("onSale");
  });

  it("★★ 审核态不受门店级影响 —— 那是平台的判断，与哪家店无关", () => {
    expect(stateOf({ status: "PENDING", onSale: false, storeOnSale: true })).toBe("PENDING");
    expect(stateOf({ status: "REJECTED", onSale: false, storeOnSale: true })).toBe("REJECTED");
  });
});
