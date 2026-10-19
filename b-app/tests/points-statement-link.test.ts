import { expect, it, vi } from "vitest";
import { preparePointStatementLink } from "../src/shared/points-statement-link";
function merchant() {
  const m = { storeNo: "S1", ensureScope: vi.fn().mockResolvedValue(undefined),
    ensureEntityGroups: vi.fn().mockResolvedValue([{ stores: [{ storeNo: "S1" }, { storeNo: "S2" }] }]),
    switchStore: vi.fn(async (storeNo: string) => { m.storeNo = storeNo; }), can: vi.fn(() => true) };
  return m;
}
it("首次通知进入等待权限再切到授权门店并定位类型及账单", async () => {
  const m = merchant();
  const trace: string[] = [];
  m.ensureScope.mockImplementation(async () => { trace.push("scope"); });
  m.switchStore.mockImplementation(async (no) => { trace.push("switch"); m.storeNo = no; });
  expect(await preparePointStatementLink(m, { storeNo: "S2", typeCode: "TYPE", statementNo: "ST" }))
    .toEqual({ typeCode: "TYPE", statementNo: "ST" });
  expect(trace[0]).toBe("scope"); expect(m.storeNo).toBe("S2");
});
it("未知门店拒绝且不切换到该门店", async () => {
  const m = merchant();
  await expect(preparePointStatementLink(m, { storeNo: "EVIL", typeCode: "T", statementNo: "ST" })).rejects.toThrow();
  expect(m.switchStore).not.toHaveBeenCalled();
});
it("目标店财务权限缺失则拒绝不能回落展示原店", async () => {
  const m = merchant(); m.can.mockImplementation(() => m.storeNo === "S1");
  await expect(preparePointStatementLink(m, { storeNo: "S2", typeCode: "T", statementNo: "ST" })).rejects.toThrow();
  expect(m.storeNo).toBe("S2");
});
