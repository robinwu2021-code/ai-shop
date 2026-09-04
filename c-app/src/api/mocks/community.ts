// 社区与自提点 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, delay, toCommunity } from "@shared/mock/db";
import type { RegionNode } from "@shared/types";
import type { ShopApi } from "../contract";

export const communityMock: Pick<ShopApi,
  "nearbyCommunities"
  | "resolveLocation"
  | "allCommunities"
  | "openRegions"
  | "regions"
> = {
  // ---------------------------------------------------------------- 社区
  async nearbyCommunities() {
    return delay(allCommunitySeeds().map(toCommunity));
  },

  /**
   * mock 里也要**如实反映「模糊坐标不匹配」**这条规则 ——
   * 恒返回第一个社区的话，端上那条降级分支在开发期一次都看不见，等于没做。
   */
  async resolveLocation(latE6, lngE6, coarse) {
    if (coarse || latE6 == null || lngE6 == null) {
      return delay({ innermostNo: null, innermostName: null, chainNos: [], coarse: !!coarse });
    }
    const first = allCommunitySeeds().map(toCommunity)[0];
    return delay(first
      ? { innermostNo: first.communityNo, innermostName: first.name, chainNos: [first.communityNo], coarse: false }
      : { innermostNo: null, innermostName: null, chainNos: [], coarse: false });
  },

  async allCommunities() {
    // mock 侧两者同源：真后端的差别是 nearby 带半径过滤，而 mock 只有一个城市的种子
    return delay(allCommunitySeeds().map(toCommunity));
  },

  async openRegions() {
    // 演示数据只有一个区。真后端是从社区的 region_code 聚合出来的
    return delay([
      {
        regionCode: "330106",
        name: "西湖区",
        cityCode: "3301",
        cityName: "杭州市",
        communityCount: allCommunitySeeds().length,
      },
    ]);
  },

  /**
   * 区划树。**只是够用的一小棵** —— 演示数据不追求全国 3000+ 区县，
   * 但形状必须与真后端一致：直辖市（市即省）、区县是叶子、没有街道。
   * 形状不一致的 mock 比没有 mock 更糟：端上按 mock 调通了，接真后端才发现走不通。
   */
  async regions(parent?: string) {
    const rows: RegionNode[] = [
      { regionCode: "33", parentCode: null, level: "PROVINCE", name: "浙江省", hasChild: true },
      { regionCode: "31", parentCode: null, level: "PROVINCE", name: "上海市", hasChild: true },
      { regionCode: "44", parentCode: null, level: "PROVINCE", name: "广东省", hasChild: true },
      { regionCode: "3301", parentCode: "33", level: "CITY", name: "杭州市", hasChild: true },
      { regionCode: "3302", parentCode: "33", level: "CITY", name: "宁波市", hasChild: true },
      // 直辖市的第二级在国标里就是它自己 —— 端上要能一路点下去，不能在这里断掉
      { regionCode: "3101", parentCode: "31", level: "CITY", name: "上海市", hasChild: true },
      { regionCode: "4419", parentCode: "44", level: "CITY", name: "东莞市", hasChild: true },
      { regionCode: "330106", parentCode: "3301", level: "DISTRICT", name: "西湖区", hasChild: false },
      { regionCode: "330108", parentCode: "3301", level: "DISTRICT", name: "滨江区", hasChild: false },
      { regionCode: "330203", parentCode: "3302", level: "DISTRICT", name: "海曙区", hasChild: false },
      { regionCode: "310115", parentCode: "3101", level: "DISTRICT", name: "浦东新区", hasChild: false },
      { regionCode: "441900", parentCode: "4419", level: "DISTRICT", name: "东莞市", hasChild: false },
    ];
    // 不传 parent = 取省级。判 `== null` 而不是 `!parent`：空串也当成「取顶层」
    return delay(rows.filter((r) => (parent ? r.parentCode === parent : r.parentCode === null)));
  },
};
