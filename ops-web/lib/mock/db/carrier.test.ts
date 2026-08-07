// 第三方运力配置的规则测试（P-5.2.4）。
//
// 这一页配错的后果不是"显示不对"，而是**订单发不出去**。所以测的全是那三条：
// 全停、启用没配密钥的、停掉还有在途单的那家。
import { beforeEach, describe, expect, it } from "vitest";
import { fulfillmentMock } from "@/lib/api/mocks/fulfillment";
import { carriers, shipments } from "@/lib/mock/db";

const cSnap = carriers.map((c) => ({ ...c }));
const sSnap = shipments.map((s) => ({ ...s, traces: [...s.traces] }));

beforeEach(() => {
  carriers.splice(0, carriers.length, ...cSnap.map((c) => ({ ...c })));
  shipments.splice(0, shipments.length, ...sSnap.map((s) => ({ ...s, traces: [...s.traces] })));
});

describe("启停运力", () => {
  it("**没配密钥的不能启用** —— 启用后下单会当场失败", async () => {
    const noKey = carriers.find((c) => !c.apiKeyConfigured)!;
    await expect(fulfillmentMock.setCarrierEnabled(noKey.carrier, true)).rejects.toThrow(/尚未配置接入密钥/);
  });

  it("**还有在途快递单的不能停用** —— 停了之后那些单的轨迹拉不回来", async () => {
    // 样本里 SF 有一个在途单
    const busy = carriers.find((c) => c.carrier === "SF")!;
    expect(shipments.some((s) => s.carrier === busy.carrier && s.status !== "DELIVERED")).toBe(true);
    await expect(fulfillmentMock.setCarrierEnabled(busy.carrier, false)).rejects.toThrow(/在途快递单/);
  });

  it("在途单都签收后就能停用", async () => {
    for (const s of shipments) if (s.carrier === "SF") s.status = "DELIVERED";
    const c = await fulfillmentMock.setCarrierEnabled("SF", false);
    expect(c.enabled).toBe(false);
    expect(c.updatedBy).toBe("admin");
  });

  it("**不能把最后一家启用的也停掉** —— 全停之后快递单无处可下", async () => {
    // 先把所有在途单标记签收，排除"在途单"那条规则的干扰
    for (const s of shipments) s.status = "DELIVERED";
    const enabled = carriers.filter((c) => c.enabled);
    expect(enabled.length).toBeGreaterThan(1); // 样本不足这条就是空转

    // 停到只剩一家
    for (const c of enabled.slice(0, -1)) await fulfillmentMock.setCarrierEnabled(c.carrier, false);
    const last = enabled[enabled.length - 1];
    await expect(fulfillmentMock.setCarrierEnabled(last.carrier, false)).rejects.toThrow(/至少要保留一家/);
  });
});

describe("接入参数", () => {
  const base = () => {
    const c = carriers.find((x) => x.carrier === "JD")!;
    return { carrier: c.carrier, name: c.name, priority: c.priority, pickupCutoff: c.pickupCutoff, slaHours: c.slaHours };
  };

  it("**优先级不能与别家重复** —— 同优先级时选哪家取决于顺序，那是隐性行为", async () => {
    const other = carriers.find((c) => c.carrier !== "JD")!;
    await expect(
      fulfillmentMock.saveCarrier({ ...base(), priority: other.priority }),
    ).rejects.toThrow(/已被别的运力占用/);
  });

  it("截单时间必须是 HH:mm", async () => {
    await expect(fulfillmentMock.saveCarrier({ ...base(), pickupCutoff: "25:00" })).rejects.toThrow(/HH:mm/);
    await expect(fulfillmentMock.saveCarrier({ ...base(), pickupCutoff: "17" })).rejects.toThrow(/HH:mm/);
  });

  it("承诺时效必须是正整数小时", async () => {
    await expect(fulfillmentMock.saveCarrier({ ...base(), slaHours: 0 })).rejects.toThrow(/正整数/);
  });

  it("合法配置落库并留痕", async () => {
    const r = await fulfillmentMock.saveCarrier({ ...base(), priority: 9, slaHours: 60, pickupCutoff: "15:30" });
    expect(r.priority).toBe(9);
    expect(r.slaHours).toBe(60);
    expect(r.updatedBy).toBe("admin");
    // 真落库：重新读一次还是新值（伪实现会在这里露馅）
    const again = (await fulfillmentMock.listCarriers()).find((c) => c.carrier === "JD")!;
    expect(again.pickupCutoff).toBe("15:30");
  });

  it("**契约里没有密钥字段** —— 密钥不该出现在前端契约里，哪怕是脱敏的", async () => {
    const list = await fulfillmentMock.listCarriers();
    for (const c of list) {
      expect(Object.keys(c)).not.toContain("apiKey");
      expect(Object.keys(c)).not.toContain("apiSecret");
    }
  });

  it("列表按优先级排序 —— 它就是「先派给谁」的顺序", async () => {
    const list = await fulfillmentMock.listCarriers();
    const ps = list.map((c) => c.priority);
    expect([...ps].sort((a, b) => a - b)).toEqual(ps);
  });
});
