import { describe, it, expect } from "vitest";
import { stockEntries, countUsableLocations } from "../src/shared/stock-entries";
import { canStartNewCount } from "../src/shared/stock-urgent";

/** 店长：三个码全有 */
const boss = (p: string) => ["biz:stock", "biz:customer", "biz:store:admin"].includes(p);
/** 店员：只有库存码 —— 报表与库位都不该看见 */
const clerk = (p: string) => p === "biz:stock";

const keys = (list: { key: string }[]) => list.map((e) => e.key);

describe("stock-entries", () => {
  it("底下那条只留进货和报损", () => {
    const { primary, more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(keys(primary)).toEqual(["purchase", "out"]);
    // 盘点与调拨**不在**底下那条里 —— 它们一周到一月才一次
    expect(keys(more)).toContain("check");
    expect(keys(more)).toContain("transfer");
  });

  it("「更多」里是六条，按多久用一次排", () => {
    const { more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(keys(more)).toEqual(["check", "transfer", "docs", "report", "locations", "suppliers"]);
  });

  it("跨店只给多门店商家，且不在那六条里", () => {
    const one = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 3 });
    expect(one.cross).toBeNull();

    const many = stockEntries({ can: boss, multiStore: true, canStartCount: true, usableLocations: 3 });
    expect(many.cross?.key).toBe("cross");
    // 它是「看」不是「去办一件事」：不该混进那六条里，也不在底下那条
    expect(keys(many.more)).not.toContain("cross");
    expect(keys(many.primary)).not.toContain("cross");
  });

  it("放货的地方不足两个时调拨不可点，并给出去处", () => {
    const { more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: 1 });
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
    const { more } = stockEntries({ can: boss, multiStore: false, canStartCount: true, usableLocations: null });
    expect(more.find((e) => e.key === "transfer")!.blocked).toBeUndefined();
  });

  it("每条按它自己那一页的权限判", () => {
    const { more, primary } = stockEntries({ can: clerk, multiStore: true, canStartCount: true, usableLocations: 3 });
    // 报表要 biz:customer、库位要 biz:store:admin —— 店员两条都看不见
    expect(keys(more)).not.toContain("report");
    expect(keys(more)).not.toContain("locations");
    expect(keys(more)).toEqual(["check", "transfer", "docs", "suppliers"]);
    expect(keys(primary)).toEqual(["purchase", "out"]);
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
    expect(keys(open.more)).toEqual(["transfer", "docs", "report", "locations", "suppliers"]);
    expect(keys(open.more)).not.toContain("check");

    // 没单开着时它照常在第一条
    const idle = stockEntries({
      can: boss, multiStore: false, canStartCount: true, usableLocations: 3,
    });
    expect(keys(idle.more)[0]).toBe("check");
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
