import { describe, it, expect } from "vitest";
import { stockEntries, countUsableLocations } from "../src/shared/stock-entries";
import { canStartNewCount } from "../src/shared/stock-urgent";

/** 店长：三个码全有 */
const boss = (p: string) => ["biz:stock", "biz:customer", "biz:store:admin"].includes(p);
/** 店员：只有库存码 —— 报表与库位都不该看见 */
const clerk = (p: string) => p === "biz:stock";

const keys = (list: { key: string }[]) => list.map((e) => e.key);

/*
 * ★ **2026-09-17 从两枚扩到四枚**（商家定的）：贴底条只放两枚时每枚 136px，
 * 而内容只需 73px。按文件头那条判据（多久用一次）往上提的顺位就是盘点、调拨。
 *
 * 这一组用例钉的不是「哪四个」，是**三条退化路径都不需要第二套布局**：
 *   盘点没了 → 三枚　·　调拨被拦 → 三枚（回抽屉，那儿写得下原因）　·　都没 → 两枚
 */
describe("stock-entries", () => {
  it("底下那条只留进货和报损", () => {
    const { primary, more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(keys(primary)).toEqual(["purchase", "out", "check", "transfer"]);
    // 盘点与调拨**不在**底下那条里 —— 它们一周到一月才一次
    expect(keys(more)).not.toContain("check");
    expect(keys(more)).not.toContain("transfer");
  });

  it("「更多」里是六条，按多久用一次排", () => {
    const { more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(keys(more)).toEqual(["docs", "report", "locations", "suppliers"]);
  });

  it("跨店只给多门店商家，且不在那六条里", () => {
    const one = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(one.cross).toBeNull();

    const many = stockEntries({ can: boss, multiStore: true, canStartCount: true, usableLocations: 3 });
    expect(many.cross?.key).toBe("cross");
    /*
     * ★ **2026-09-17 收进抽屉**（店主：库存页顶部要简洁）。
     * 它原本贴在总览卡的四个数下面，占着首屏一整行。
     *
     * **不是删掉** —— 它是跨店总览那一页的唯一入口，删了就再也进不去。
     * `cross` 这个返回值仍然留着：页面用它判断「要不要在抽屉里摆这一条」，
     * 而不是自己再算一遍 multiStore。
     */
    expect(keys(many.more)).toContain("cross");
    expect(keys(many.primary)).not.toContain("cross");
  });

  it("放货的地方不足两个时调拨不可点，并给出去处", () => {
    const { primary, more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 1 });
    // ★ 被拦住的不往上提：条上放不下一句解释，抽屉里放得下
    expect(keys(primary)).not.toContain("transfer");
    const transfer = more.find((e) => e.key === "transfer")!;
    expect(transfer.blocked).toBeTruthy();
    expect(transfer.blocked!.reasonKey).toBe("stock.entryBlocked.transfer");
    expect(transfer.blocked!.params).toEqual({ n: 1 });
    expect(transfer.blocked!.route).toBe("/pages/locations/index");
  });

  it("没有库位权限的人不给去处，但仍要看到原因", () => {
    const { more } = stockEntries({ can: clerk, multiStore: false, canStartCount: true, usableLocations: 1 });
    const transfer = more.find((e) => e.key === "transfer")!;
    expect(transfer.blocked!.reasonKey).toBe("stock.entryBlocked.transfer");
    // 去添加那一页要 biz:store:admin —— 送他过去就是送到一道被拒的门前
    expect(transfer.blocked!.route).toBeUndefined();
  });

  it("还没取到库位数时不灰掉 —— 不知道不等于不足两个", () => {
    // 「不知道」不等于「不足两个」：还没取到时照样提上条，不该先灰再变回来
    const { primary, more } = stockEntries({
      can: boss, multiStore: false, canStartCount: true, usableLocations: null,
    });
    expect(keys(primary)).toContain("transfer");
    expect(more.find((e) => e.key === "transfer")).toBeUndefined();
  });

  it("每条按它自己那一页的权限判", () => {
    const { more, primary } = stockEntries({ can: clerk, multiStore: true, canStartCount: true, usableLocations: 3 });
    // 报表要 biz:customer、库位要 biz:store:admin —— 店员两条都看不见
    expect(keys(more)).not.toContain("report");
    expect(keys(more)).not.toContain("locations");
    // 店员也是多门店商家的店员，跨店那一条跟着 multiStore 走
    expect(keys(more)).toEqual(["docs", "suppliers", "cross"]);
    expect(keys(primary)).toEqual(["purchase", "out", "check", "transfer"]);
  });

  it("一个码都没有的人：菜单是空的，不是一排点不动的名字", () => {
    const { primary, more } = stockEntries({
      can: () => false, multiStore: true, canStartCount: true, usableLocations: 3,
    });
    expect(primary).toEqual([]);
    expect(more).toEqual([]);
  });

  it("有盘点单开着时，「盘点」让位给「继续盘点」", () => {
    const open = stockEntries({
      can: boss, multiStore: false, canStartCount: false, usableLocations: 3,
    });
    // 整条拿掉，不是灰掉 —— 这件事正由上面那条「继续盘点」接着
    expect(keys(open.more)).toEqual(["docs", "report", "locations", "suppliers"]);
    expect(keys(open.primary)).toEqual(["purchase", "out", "transfer"]);
    expect(keys(open.primary)).not.toContain("check");

    // 没单开着时它照常占条上第三枚
    const idle = stockEntries({
      can: boss, multiStore: false, canStartCount: true, usableLocations: 3,
    });
    expect(keys(idle.primary)[2]).toBe("check");
  });

  it("canStartNewCount：有单号就开不了新的", () => {
    expect(canStartNewCount(null)).toBe(true);
    expect(canStartNewCount(undefined)).toBe(true);
    expect(canStartNewCount({ openCountNo: null } as never)).toBe(true);
    expect(canStartNewCount({ openCountNo: "" } as never)).toBe(true);
    expect(canStartNewCount({ openCountNo: "CNT-24082601" } as never)).toBe(false);
  });

  it("在途不算一个放货的地方", () => {
    expect(
      countUsableLocations([{ kind: "STORE" }, { kind: "WAREHOUSE" }, { kind: "TRANSIT" }]),
    ).toBe(2);
  });
});
