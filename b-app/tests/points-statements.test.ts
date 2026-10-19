// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
const m = vi.hoisted(() => ({ single: vi.fn(), lines: vi.fn(), list: vi.fn(), confirm: vi.fn(), dispute: vi.fn(), dialog: vi.fn(), store: "S1" }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: m.dialog }));
vi.mock("@/stores/merchant", () => ({ useMerchantStore: () => ({ get storeNo() { return m.store; }, can: () => true }) }));
vi.mock("@/api", () => ({ api: { mStorePointStatement: m.single, mStorePointStatementLines: m.lines, mStorePointStatements: m.list, mConfirmStorePointStatement: m.confirm, mDisputeStorePointStatement: m.dispute } }));
import Panel from "@/components/points/StorePointStatements.vue";
const row = { id: 1, statementNo: "ST1", typeCode: "TYPE", storeNo: "S1", fromMillis: 1, toMillis: 2, state: "DRAFT", revision: 7, points: 100, revisions: [] };
beforeEach(() => { vi.clearAllMocks(); m.store = "S1"; m.single.mockResolvedValue(row); m.lines.mockResolvedValue({ rows: [{ orderNo: "ORDER", subOrderNo: "SUB", points: 25 }], next: 1, hasMore: false }); m.list.mockResolvedValue({ rows: [row], next: 1, hasMore: false }); m.dialog.mockResolvedValue(true); m.confirm.mockResolvedValue({}); m.dispute.mockResolvedValue({}); });
async function render() { const w = mount(Panel, { props: { typeCode: "TYPE", storeNo: "S1" } }); await flushPromises(); return w; }
it("确认提交展示版本且失败重试沿用操作号", async () => {
  m.confirm.mockRejectedValueOnce(new Error("offline"));
  const w = await render();
  await w.get('[data-test="confirm"]').trigger("click"); await flushPromises();
  await w.get('[data-test="confirm"]').trigger("click"); await flushPromises();
  expect(m.confirm).toHaveBeenCalledTimes(2);
  expect(m.confirm.mock.calls[0][0]).toMatchObject({ statementNo: "ST1", revision: 7 });
  expect(m.confirm.mock.calls[1][0].requestId).toBe(m.confirm.mock.calls[0][0].requestId);
  expect(m.confirm.mock.calls[0][0]).not.toHaveProperty("storeNo"); w.unmount();
});
it("异议要求填写理由并携带当前修订", async () => {
  const w = await render();
  await w.get('[data-test="dispute"]').trigger("click"); await flushPromises();
  expect(m.dispute).not.toHaveBeenCalled();
  await w.get("textarea").setValue("金额不一致");
  await w.get('[data-test="dispute"]').trigger("click"); await flushPromises();
  expect(m.dispute).toHaveBeenCalledWith(expect.objectContaining({ revision: 7, reason: "金额不一致" })); w.unmount();
});
it("确认弹窗期间切店不发送原店命令", async () => {
  let finish!: (value: boolean) => void;
  m.dialog.mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  const w = await render();
  await w.get('[data-test="confirm"]').trigger("click");
  m.store = "S2"; finish(true); await flushPromises();
  expect(m.confirm).not.toHaveBeenCalled(); w.unmount();
});

it("通知目标账单直接读取，不扫描列表历史", async () => {
  const w = mount(Panel, { props: { typeCode: "TYPE", storeNo: "S1", targetStatement: "ST1" } });
  await flushPromises();
  expect(m.single).toHaveBeenCalledWith("TYPE", "ST1"); expect(m.list).not.toHaveBeenCalled();
  expect(w.text()).toContain("ST1"); w.unmount();
});
it("明细查询携带选择修订并展示来源订单", async () => {
  const w = await render();
  await w.get('[data-test="details"]').trigger("click"); await flushPromises();
  expect(m.lines).toHaveBeenCalledWith("TYPE", "ST1", 7, 0, 20);
  expect(w.text()).toContain("ORDER"); expect(w.text()).toContain("25"); w.unmount();
});
