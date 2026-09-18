// 社区与自提点 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { allCommunitySeeds, db, delay, toCommunity } from "@shared/mock/db";
import type { RegionNode } from "@shared/types";
import { ApiError } from "@shared/net/http-client";
import type { ShopApi } from "../contract";

/** 后端 `ErrorCode.NOT_FOUND`。写死一个数是因为 mock 的职责就是长得像服务端 */
const NOT_FOUND = 10404;

export const communityMock: Pick<ShopApi,
  "nearbyCommunities"
  | "resolveLocation"
  | "allCommunities"
  | "communityDetail"
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
    /*
     * **模糊坐标仍然给得出区县** —— 而那一条恰恰是端上按区看货的依据。
     * mock 里不给的话，「按区兜底」这条分支在开发期永远走不到，
     * 端上会一直落在「不带条件去要商品」那一支上，也就是全平台。
     */
    const seed = allCommunitySeeds().find((c) => c.regionCode);
    const district = seed?.regionCode?.slice(0, 6) ?? null;
    const districtName = district
      ? db.regionSeeds.find((r) => r.regionCode === district)?.name ?? null
      : null;
    if (coarse || latE6 == null || lngE6 == null) {
      // 没坐标就连区也推不出来 —— 那一格是空态要位置，不是「随便看看」
      const hasCoords = latE6 != null && lngE6 != null;
      // 走 toCommunity 而不是直接读 seed.name —— 种子里的名字是三语对象，
      // 直接塞进去类型就不对，而 vue-tsc 会拦下来（那正是它在这儿的价值）
      const nearSeed = hasCoords ? allCommunitySeeds()[0] : undefined;
      const near = nearSeed ? toCommunity(nearSeed) : undefined;
      return delay({
        innermostNo: null, innermostName: null, chainNos: [], coarse: !!coarse,
        regionCode: hasCoords ? district : null,
        regionName: hasCoords ? districtName : null,
        // mock 里也要真给出「最近的聚落」—— 不给的话端上那条默认归属的分支
        // 在开发期永远走不到，与改造前长得一模一样（首页空着）
        nearestNo: near?.communityNo ?? null,
        nearestName: near?.name ?? null,
        nearestDistanceM: near ? 20000 : -1,
        /*
         * **mock 也要给建筑级的那一档。** 只给聚落名的话，
         * 「取名取到建筑」这条在开发期一次都看不见，而它正是这一轮的重点。
         * `source=PLACE_DB` 顺带让「库里命中」那条分支跑得到。
         */
        place: hasCoords
          ? { name: "龙华区地域馆", address: "深圳市龙华区观澜大道 155 号",
            kind: "POI" as const, source: "PLACE_DB" as const, stale: false }
          : null,
      });
    }
    const first = allCommunitySeeds().map(toCommunity)[0];
    return delay(first
      ? {
        innermostNo: first.communityNo, innermostName: first.name,
        chainNos: [first.communityNo], coarse: false,
        regionCode: district, regionName: districtName,
        // 落进围栏了就不给「最近的」：两个主语迟早会被选错
        nearestNo: null, nearestName: null, nearestDistanceM: -1,
        // 落进围栏时 place 就是这个聚落 —— 它比任何外部地名都权威
        place: { name: first.name, address: first.address ?? null,
          kind: "COMMUNITY" as const, source: "COMMUNITY" as const, stale: false },
      }
      : {
        innermostNo: null, innermostName: null, chainNos: [], coarse: false,
        regionCode: district, regionName: districtName,
        nearestNo: null, nearestName: null, nearestDistanceM: -1,
        place: null,
      });
  },

  async communityDetail(communityNo) {
    const seed = allCommunitySeeds().find((c) => c.communityNo === communityNo);
    /*
     * **抛 ApiError，不是普通 Error。** 端上「这个聚落还在不在」那条判断
     * 靠的就是「后端答了话」（ApiError）与「网络不通」（别的异常）的区别 ——
     * mock 抛普通 Error 的话，那条判断在 mock 下是死的，
     * 而开发期看起来一切正常（归属照样留着，没有任何报错）。
     */
    if (!seed) throw new ApiError(NOT_FOUND, "社区不存在");
    return delay(toCommunity(seed));
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
