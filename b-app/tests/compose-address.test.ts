import { describe, expect, it } from "vitest";
import { composeAddress } from "@/utils/geo";

/**
 * 地图选点结果拼成一行门牌地址。
 *
 * <p>高德给回来的是两截：`address`（标准地址，到街道/路号）与 `name`（POI 名）。
 * 两截的关系不固定 —— 有时 POI 名已经在地址里，有时不在。
 * 无脑拼接的后果是「深圳市龙华区福安雅园福安雅园」，而这一行会落进
 * `mch_store.address`、印在小票上、给骑手看。
 *
 * <p>四条分支各对应一种真实回包，都不是类型能挡住的。
 */
describe("选点结果拼门牌地址", () => {
  it("★★ POI 名不在地址里 → 接在后面", () => {
    expect(
      composeAddress({ address: "深圳市龙华区福城街道", name: "福安雅园" } as never),
    ).toBe("深圳市龙华区福城街道福安雅园");
  });

  it("★★★ POI 名已经在地址里 → 不重复接 —— 否则「…福安雅园福安雅园」印在小票上", () => {
    expect(
      composeAddress({ address: "深圳市龙华区福安雅园", name: "福安雅园" } as never),
    ).toBe("深圳市龙华区福安雅园");
  });

  it("★ 只有 POI 名（地址空）→ 用 POI 名，不返回空串", () => {
    // 地址空而 name 有值是真会出现的：在纯 POI 上点选时高德只给名字。
    // 返回空串的话，店主看到「地址已保存」而那一行是空的。
    expect(composeAddress({ address: "", name: "福安雅园" } as never)).toBe("福安雅园");
  });

  it("★ 只有地址（POI 名是空白）→ 原样返回，不多出尾巴", () => {
    // name 是几个空格而不是空串 —— 不 trim 的话会拼出「深圳市龙华区   」
    expect(composeAddress({ address: "深圳市龙华区", name: "  " } as never)).toBe("深圳市龙华区");
  });
});
