// 风控规则测试（P-16.2）。「必须带原因与期限」这条是本域的底线：
// 无期限拉黑没有申诉出口，那是产品事故不是风控严格。
import { beforeEach, describe, expect, it } from "vitest";
import { riskMock } from "@/lib/api/mocks/risk";
import { blacklists, riskEvents, riskRules } from "./risk";

const E0 = JSON.parse(JSON.stringify(riskEvents)) as typeof riskEvents;
const B0 = JSON.parse(JSON.stringify(blacklists)) as typeof blacklists;
const R0 = JSON.parse(JSON.stringify(riskRules)) as typeof riskRules;
beforeEach(() => {
  riskEvents.length = 0; riskEvents.push(...(JSON.parse(JSON.stringify(E0)) as typeof riskEvents));
  blacklists.length = 0; blacklists.push(...(JSON.parse(JSON.stringify(B0)) as typeof blacklists));
  riskRules.length = 0; riskRules.push(...(JSON.parse(JSON.stringify(R0)) as typeof riskRules));
});

describe("事件处置（P-16.2.1–3）", () => {
  it("排除也必须写理由 —— 下次同一主体再命中时要知道上次为什么放过", async () => {
    await expect(riskMock.decideRiskEvent("RK9001", false, "  ")).rejects.toThrow(/处置结论必填/);
  });

  it("确认与排除都落库", async () => {
    const a = await riskMock.decideRiskEvent("RK9001", true, "确认为工作室批量注册");
    expect(a.status).toBe("CONFIRMED");
    const b = await riskMock.decideRiskEvent("RK9002", false, "促销导致的订单突增，非刷单");
    expect(b.status).toBe("DISMISSED");
  });

  it("已处置的事件不能重复处置", async () => {
    await expect(riskMock.decideRiskEvent("RK9004", true, "再判一次")).rejects.toThrow(/已处置/);
  });

  it("三类事件同表，按 type 筛得出来", async () => {
    const page = await riskMock.listRiskEvents({ type: "ABNORMAL_FISSION", size: 100 });
    expect(page.records.length).toBeGreaterThan(0);
    expect(page.records.every((e) => e.type === "ABNORMAL_FISSION")).toBe(true);
  });

  it("事件带的证据能指回归因链路（跨域联动的依据）", async () => {
    const page = await riskMock.listRiskEvents({ type: "ABNORMAL_FISSION", size: 100 });
    const e = page.records.find((x) => x.eventNo === "RK9001")!;
    expect(e.refs.some((r) => r.startsWith("AT"))).toBe(true);
  });
});

describe("黑名单（P-16.2.4）", () => {
  it("原因必填", async () => {
    await expect(
      riskMock.addBlacklist({ subjectType: "USER", subject: "张三", reason: "", until: "2026-12-31T00:00:00Z" }),
    ).rejects.toThrow(/原因必填/);
  });

  it("到期时间必填 —— 不允许无期限拉黑", async () => {
    await expect(
      riskMock.addBlacklist({ subjectType: "USER", subject: "张三", reason: "刷单", until: "" }),
    ).rejects.toThrow(/到期时间必填/);
  });

  it("到期时间必须晚于当前", async () => {
    await expect(
      riskMock.addBlacklist({ subjectType: "USER", subject: "张三", reason: "刷单", until: "2020-01-01T00:00:00Z" }),
    ).rejects.toThrow(/晚于当前/);
  });

  it("同一主体不能重复拉黑（生效中）", async () => {
    await expect(
      riskMock.addBlacklist({ subjectType: "USER", subject: "用户8820", reason: "重复", until: "2026-12-31T00:00:00Z" }),
    ).rejects.toThrow(/已在生效中/);
  });

  it("合法拉黑落库", async () => {
    const b = await riskMock.addBlacklist({ subjectType: "DEVICE", subject: "aa11bb22", reason: "批量注册", until: "2027-01-01T00:00:00Z" });
    expect(b.active).toBe(true);
    expect(b.appealStatus).toBe("NONE");
  });
});

describe("解禁申诉裁决", () => {
  it("裁决说明必填（被拉黑者会看到）", async () => {
    await expect(riskMock.decideBlacklistAppeal("BL9002", true, " ")).rejects.toThrow(/裁决说明必填/);
  });

  it("接受申诉 = 解除拉黑，但记录保留（留痕不是删除）", async () => {
    const b = await riskMock.decideBlacklistAppeal("BL9002", true, "核实为帮家人注册，非工作室");
    expect(b.appealStatus).toBe("ACCEPTED");
    expect(b.active).toBe(false);
    expect(blacklists.some((x) => x.blackNo === "BL9002")).toBe(true);
  });

  it("驳回申诉不解除拉黑", async () => {
    const b = await riskMock.decideBlacklistAppeal("BL9002", false, "设备指纹与已确认工作室一致");
    expect(b.appealStatus).toBe("REJECTED");
    expect(b.active).toBe(true);
  });

  it("没有待裁决申诉的记录不能裁决", async () => {
    await expect(riskMock.decideBlacklistAppeal("BL9001", true, "x")).rejects.toThrow(/没有待裁决/);
  });
});

describe("拦截规则（P-16.2.5）", () => {
  it("阈值必须大于 0（0 等于全量拦截）", async () => {
    await expect(riskMock.saveRiskRule("FAKE_ORDER", 0, false)).rejects.toThrow(/大于 0/);
  });

  it("保存阈值与自动拦截开关", async () => {
    const r = await riskMock.saveRiskRule("FAKE_ORDER", 20, true);
    expect(r).toMatchObject({ threshold: 20, autoBlock: true });
  });
});
