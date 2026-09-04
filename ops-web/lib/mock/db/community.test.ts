// 社区与自提点的规则测试（P-2.1 / P-2.2）。锁的是**业务规则**而不是数据：
// 零报酬、状态机、数据域裁剪，这三条错了都会变成产品事实。
import { beforeEach, describe, expect, it } from "vitest";
import { communityMock } from "@/lib/api/mocks/community";
import { NEIGHBOR_RISK_ACCEPT_COUNT } from "@/lib/constants";
import { communities, pickups } from "./community";

const C0 = JSON.parse(JSON.stringify(communities)) as typeof communities;
const P0 = JSON.parse(JSON.stringify(pickups)) as typeof pickups;
beforeEach(() => {
  communities.length = 0; communities.push(...(JSON.parse(JSON.stringify(C0)) as typeof communities));
  pickups.length = 0; pickups.push(...(JSON.parse(JSON.stringify(P0)) as typeof pickups));
});

describe("社区网格", () => {
  it("开城开关落库", async () => {
    await communityMock.setCommunityOpen("C001", false);
    const page = await communityMock.listCommunities({ keyword: "C001" });
    expect(page.records[0].opened).toBe(false);
  });

  it("围栏半径不能为 0（等于谁都不覆盖，是配置事故）", async () => {
    await expect(communityMock.setCommunityFence("C001", 0)).rejects.toThrow(/大于 0/);
  });

  it("按开城状态筛选（下拉给的是字符串 '1'/'0'，不是 boolean）", async () => {
    const closed = await communityMock.listCommunities({ opened: "0", size: 100 });
    expect(closed.records.every((c) => !c.opened)).toBe(true);
    expect(closed.records.length).toBeGreaterThan(0);
  });

  it("归档后默认列表不再出现", async () => {
    await communityMock.archiveCommunity("C004");
    const page = await communityMock.listCommunities({ size: 100 });
    expect(page.records.some((c) => c.communityNo === "C004")).toBe(false);
  });
});

describe("围栏影响预览（T9）", () => {
  it("★ 差值要是真算出来的 —— 回常量的 mock 会让那一屏永远显示「+0」，界面对不对根本看不出", async () => {
    const now = await communityMock.fenceImpact("C001");
    const bigger = await communityMock.fenceImpact("C001", 1500);
    expect(now.currentRadiusM).toBe(800);
    expect(bigger.previewInside - bigger.currentInside)
      .toBeGreaterThan(0);   // 种子里有两条落在 800 与 1500 之间
    const smaller = await communityMock.fenceImpact("C001", 200);
    expect(smaller.previewInside).toBeLessThan(smaller.currentInside);
  });

  it("★ 分母一起给 —— 「多进来 0 户」在一个没几条地址有坐标的库里说明不了任何事", async () => {
    const vo = await communityMock.fenceImpact("C001", 1500);
    expect(vo.addressesWithCoords).toBeGreaterThan(0);
  });

  it("没标点的聚落给 0/0，不抛错 —— 报错会让整页打不开，而缺口反而看不见", async () => {
    const vo = await communityMock.fenceImpact("C003", 900);
    expect(vo.currentInside).toBe(0);
    expect(vo.previewInside).toBe(0);
    expect(vo.previewRadiusM).toBe(900);
  });
});

describe("建楼（T9）", () => {
  it("★★ 街道从父级继承、围栏 150 不是 1000", async () => {
    const b = await communityMock.createBuilding({ name: "锦绣花园 3 幢", parentNo: "C001" });
    expect(b.regionCode).toBe("330106002");
    expect(b.fenceRadius).toBe(150);
    expect(b.parentNo).toBe("C001");
  });

  it("★★★ 归属只做两层：楼底下不许再挂楼", async () => {
    const b = await communityMock.createBuilding({ name: "锦绣花园 5 幢", parentNo: "C001" });
    await expect(communityMock.createBuilding({ name: "501 室", parentNo: b.communityNo }))
      .rejects.toThrow(/两层/);
  });

  it("父级还没有街道就不许建楼 —— 建出来一样是错的，只是错得更隐蔽", async () => {
    await expect(communityMock.createBuilding({ name: "梧桐苑 1 幢", parentNo: "C003" }))
      .rejects.toThrow(/街道/);
  });
});

describe("自提点", () => {
  it("ADR-005：邻里自提点零报酬，配费率直接抛错", async () => {
    await expect(communityMock.setPickupServiceFee("P004", 100)).rejects.toThrow(/零报酬/);
  });

  it("常驻点可以配费率并落库", async () => {
    await communityMock.setPickupServiceFee("P001", 180);
    const page = await communityMock.listPickups({ keyword: "P001" });
    expect(page.records[0].serviceFeeRate).toBe(180);
  });

  it("费率不能为负", async () => {
    await expect(communityMock.setPickupServiceFee("P001", -1)).rejects.toThrow(/负/);
  });

  it("状态机：迁移中只能收尾成停用，不能直接回到启用", async () => {
    await expect(communityMock.setPickupStatus("P006", "ACTIVE")).rejects.toThrow(/不允许/);
    await communityMock.setPickupStatus("P006", "SUSPENDED");
    const page = await communityMock.listPickups({ keyword: "P006" });
    expect(page.records[0].status).toBe("SUSPENDED");
  });

  it("数据域裁剪：带 communityNo 只剩该社区的点", async () => {
    const page = await communityMock.listPickups({ communityNo: "C003", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((p) => p.communityNo === "C003")).toBe(true);
  });
});

describe("临时点职业化风控（P-2.2.5）", () => {
  it("只列 NEIGHBOR 且承接次数达阈值的点", async () => {
    const page = await communityMock.listRiskyNeighborPickups({ size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    for (const p of page.records) {
      expect(p.type).toBe("NEIGHBOR");
      expect(p.acceptCount30d).toBeGreaterThanOrEqual(NEIGHBOR_RISK_ACCEPT_COUNT);
    }
  });

  it("常驻点承接再多也不进这个队列（它本来就是干这个的）", async () => {
    const page = await communityMock.listRiskyNeighborPickups({ size: 100 });
    expect(page.records.some((p) => p.pickupNo === "P001")).toBe(false);
  });
});
