// 快递与运费的规则测试（P-5.2）。
//
// 测的是「配错了会一直错下去」的那几条：运单号重号会把两单轨迹搅在一起、
// 超区同一区域两条规则命中哪条取决于顺序、默认模板被归档之后新商家没模板可用。
import { beforeEach, describe, expect, it } from "vitest";
import { fulfillmentMock } from "@/lib/api/mocks/fulfillment";
import { freightTemplates, shipments } from "@/lib/mock/db";
import { MIN_FIRST_WEIGHT_GRAM } from "@/lib/constants";

const shipSnapshot = shipments.map((s) => ({ ...s, traces: [...s.traces] }));
const tplSnapshot = freightTemplates.map((t) => ({ ...t, outOfRange: t.outOfRange.map((r) => ({ ...r })) }));

beforeEach(() => {
  shipments.splice(0, shipments.length, ...shipSnapshot.map((s) => ({ ...s, traces: [...s.traces] })));
  freightTemplates.splice(0, freightTemplates.length,
    ...tplSnapshot.map((t) => ({ ...t, outOfRange: t.outOfRange.map((r) => ({ ...r })) })));
});

describe("快递与轨迹（P-5.2.1 / 5.2.2）", () => {
  it("**同一承运商下运单号不能重号** —— 重号会把两单的轨迹搅在一起", async () => {
    const [a, b] = shipments.filter((s) => s.status !== "DELIVERED");
    // 先把 b 换成 a 的承运商，制造出「同承运商 + 同单号」的场景
    b.carrier = a.carrier;
    await expect(
      fulfillmentMock.updateWaybill({ shipmentNo: b.shipmentNo, waybillNo: a.waybillNo, reason: "录错了" }),
    ).rejects.toThrow(/已被.*占用/);
  });

  it("不同承运商可以有相同的单号（单号只在承运商内唯一）", async () => {
    const [a, b] = shipments.filter((s) => s.status !== "DELIVERED");
    expect(a.carrier).not.toBe(b.carrier);
    const r = await fulfillmentMock.updateWaybill({
      shipmentNo: b.shipmentNo, waybillNo: a.waybillNo, reason: "承运商重新出单",
    });
    expect(r.waybillNo).toBe(a.waybillNo);
  });

  it("**已签收的不许改单号** —— 货都到了再改，等于把一条已完成的轨迹指向别处", async () => {
    const done = shipments.find((s) => s.status === "DELIVERED")!;
    await expect(
      fulfillmentMock.updateWaybill({ shipmentNo: done.shipmentNo, waybillNo: "SF0000000001", reason: "改一下" }),
    ).rejects.toThrow(/已签收/);
  });

  it("换单号必须写原因，且这条动作要出现在轨迹最前面", async () => {
    const s = shipments.find((x) => x.status === "IN_TRANSIT")!;
    await expect(
      fulfillmentMock.updateWaybill({ shipmentNo: s.shipmentNo, waybillNo: "SF0000000002", reason: " " }),
    ).rejects.toThrow(/原因/);

    const r = await fulfillmentMock.updateWaybill({
      shipmentNo: s.shipmentNo, waybillNo: "SF0000000002", reason: "承运商重新出单",
    });
    expect(r.traces[0].text).toContain("承运商重新出单");
  });

  it("按承运商与状态筛选", async () => {
    const ex = await fulfillmentMock.listShipments({ status: "EXCEPTION", size: 100 });
    expect(ex.records.every((s) => s.status === "EXCEPTION")).toBe(true);
    expect(ex.records.length).toBeGreaterThan(0); // 样本里必须有疑难件，否则这条断言是空转
  });
});

describe("运费模板与超区（P-5.2.3）", () => {
  const base = {
    name: "测试模板", firstWeightGram: 1000, firstFee: 800,
    addWeightGram: 500, addFee: 200, freeThreshold: 0, isDefault: false,
    outOfRange: [],
  };

  it(`首重不得少于 ${MIN_FIRST_WEIGHT_GRAM} 克 —— 首重为 0 意味着拿起来就收首重费`, async () => {
    await expect(
      fulfillmentMock.saveFreightTemplate({ ...base, firstWeightGram: 0 }),
    ).rejects.toThrow(/首重不得少于/);
  });

  it("续重单位为 0 要拒绝，否则续重费无从计算", async () => {
    await expect(
      fulfillmentMock.saveFreightTemplate({ ...base, addWeightGram: 0 }),
    ).rejects.toThrow(/续重单位/);
  });

  it("**同一区域不能配两条超区规则** —— 命中哪条取决于顺序，那是隐性行为", async () => {
    await expect(
      fulfillmentMock.saveFreightTemplate({
        ...base,
        outOfRange: [
          { region: "西藏自治区", action: "REJECT", surcharge: 0 },
          { region: "西藏自治区", action: "SURCHARGE", surcharge: 1000 },
        ],
      }),
    ).rejects.toThrow(/重复/);
  });

  it("不配送的区域填了加价额要**报错而不是静默清零**", async () => {
    await expect(
      fulfillmentMock.saveFreightTemplate({
        ...base,
        outOfRange: [{ region: "西藏自治区", action: "REJECT", surcharge: 500 }],
      }),
    ).rejects.toThrow(/不能同时填加价额/);
  });

  it("加价配送的加价额必须大于 0，否则这条规则等于没配", async () => {
    await expect(
      fulfillmentMock.saveFreightTemplate({
        ...base,
        outOfRange: [{ region: "青海省", action: "SURCHARGE", surcharge: 0 }],
      }),
    ).rejects.toThrow(/必须大于 0/);
  });

  it("合法模板落库，且新模板拿到新编号", async () => {
    const before = freightTemplates.length;
    const t = await fulfillmentMock.saveFreightTemplate({
      ...base,
      outOfRange: [{ region: "海南省", action: "SURCHARGE", surcharge: 1500 }],
    });
    expect(t.templateNo).toMatch(/^FT\d+$/);
    expect(freightTemplates.length).toBe(before + 1);
    expect(t.updatedBy).toBe("admin");
    // 真落库：重新读一次能找到（伪实现会在这里露馅）
    expect((await fulfillmentMock.listFreightTemplates()).records.some((x) => x.templateNo === t.templateNo)).toBe(true);
  });

  it("改已有模板是更新而不是新增", async () => {
    const before = freightTemplates.length;
    const exist = freightTemplates.find((t) => !t.isDefault)!;
    const t = await fulfillmentMock.saveFreightTemplate({ ...exist, name: "改过名字" });
    expect(t.templateNo).toBe(exist.templateNo);
    expect(freightTemplates.length).toBe(before);
  });

  it("**默认模板不能归档** —— 归档之后新商家没有模板可用", async () => {
    const def = freightTemplates.find((t) => t.isDefault)!;
    await expect(fulfillmentMock.archiveFreightTemplate(def.templateNo)).rejects.toThrow(/默认模板/);
  });

  it("归档是软删除：默认列表看不到，但数据还在（历史订单的运费依据不能被抹掉）", async () => {
    const t = freightTemplates.find((x) => !x.isDefault)!;
    await fulfillmentMock.archiveFreightTemplate(t.templateNo);

    const live = (await fulfillmentMock.listFreightTemplates()).records;
    expect(live.some((x) => x.templateNo === t.templateNo)).toBe(false);

    const all = (await fulfillmentMock.listFreightTemplates({ showArchived: true })).records;
    expect(all.some((x) => x.templateNo === t.templateNo)).toBe(true);

    await fulfillmentMock.unarchiveFreightTemplate(t.templateNo);
    expect((await fulfillmentMock.listFreightTemplates()).records.some((x) => x.templateNo === t.templateNo)).toBe(true);
  });
});
