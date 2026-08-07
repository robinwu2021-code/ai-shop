// mock 落盘往返测试。
//
// 为什么值得单独一个文件：这一类 bug 已经出现过**三次**（商家开的团与报价、
// 新建的商品、配的营销活动），症状完全一样 —— 操作成功、刷新就没了，
// 不报错，只能靠手点撞见。根因是 persist 与 restore 各有一份手写字段列表。
//
// 现在 persist 改成「默认全存 + 排除瞬时字段」，restore 也按同一套规则遍历回填。
// 这个测试守的就是这条对称性：**写进去的一定读得回来**。
import { beforeEach, describe, expect, it } from "vitest";
import { db, persist, restoreDb } from "@shared/mock/db";
import { clearStorage } from "./setup";

/** persist 有 60ms 防抖（合并连续写），测试要等它真的落盘 */
function flush(): Promise<void> {
  return new Promise((r) => setTimeout(r, 100));
}

describe("mock 落盘：写进去的读得回来", () => {
  beforeEach(() => {
    clearStorage();
  });

  it("B 端产生的写操作全部可持久化", async () => {
    db.merchant = {
      ...db.merchant,
      merchantNo: "M002",
      name: "张记粮油",
      status: "ACTIVE",
      isPickupPoint: true,
    };
    db.store = {
      announcement: "今天到了新米",
      openHours: "06:30–21:00",
      address: "阳光里南门",
      featured: [],
    };
    db.campaigns = [
      {
        campaignNo: "CP1",
        merchantNo: "M002",
        type: "FULL_CUT",
        name: "开业满减",
        status: "RUNNING",
        startAt: 1,
        endAt: 2,
        goodsNos: [],
        usedCount: 0,
      },
    ];
    db.favoriteStores = ["M002"];

    persist();
    await flush();

    // 清空内存态，模拟「重新打开 app」
    db.merchant = { ...db.merchant, merchantNo: "", name: "", status: "NONE" };
    db.store = { announcement: "", openHours: "", address: "", featured: [] };
    db.campaigns = [];
    db.favoriteStores = [];

    restoreDb();

    expect(db.merchant.merchantNo).toBe("M002");
    expect(db.merchant.name).toBe("张记粮油");
    expect(db.store.announcement).toBe("今天到了新米");
    expect(db.campaigns).toHaveLength(1);
    expect(db.campaigns[0]!.name).toBe("开业满减");
    expect(db.favoriteStores).toEqual(["M002"]);
  });

  it("商家改的商品价格与库存能读回", async () => {
    const seed = db.goodsSeeds[0]!;
    const goodsNo = seed.goodsNo;
    seed.skus[0]!.price = 12345;
    seed.skus[0]!.stock = 7;
    seed.onSale = false;

    persist();
    await flush();

    // 还原成另一组值，确认 restore 真的覆盖回来了（而不是恰好没变）
    seed.skus[0]!.price = 1;
    seed.skus[0]!.stock = 1;
    seed.onSale = true;

    restoreDb();

    const after = db.goodsSeeds.find((g) => g.goodsNo === goodsNo)!;
    expect(after.skus[0]!.price).toBe(12345);
    expect(after.skus[0]!.stock).toBe(7);
    expect(after.onSale).toBe(false);
  });

  it("新增一个 db 字段时默认就会被持久化（防的是白名单漏配）", async () => {
    // 这条断言的对象不是某个具体字段，而是**策略本身**：
    // 只要不在 TRANSIENT 名单里，就该进落盘。以前是反过来的 —— 不在白名单里就丢。
    const raw = () => JSON.parse((uni.getStorageSync("sh_mock_db") as string) || "{}");

    persist();
    await flush();

    const keys = Object.keys(raw());
    for (const k of ["orders", "cart", "campaigns", "goodsSeeds", "favoriteStores", "store"]) {
      expect(keys, `${k} 没有进落盘`).toContain(k);
    }
    // 纯种子不该进：它们来自代码，存下来会在改版后读回旧文案
    for (const k of ["communitySeeds", "merchantSeeds"]) {
      expect(keys, `${k} 是种子数据，不该落盘`).not.toContain(k);
    }
  });
});
