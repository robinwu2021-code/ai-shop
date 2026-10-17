import { expect, it, vi } from "vitest";
const client = vi.hoisted(() => ({ get: vi.fn().mockResolvedValue({}), post: vi.fn().mockResolvedValue({}) }));
vi.mock("./http-client", () => ({ client }));
import { financeHttp } from "./https/finance";
it("sends store scoped runtime integration changes without client operator", async () => {
  const command = { requestId: "C1", storeNo: "ST1", scene: "ONLINE" as const, mode: "DRAINING" as const, earnEnabled: false, redeemEnabled: false, expectedVersion: 0, reason: "stop admission" };
  await financeHttp.changeOrderPointsIntegration(command);
  expect(client.post).toHaveBeenCalledWith("/ops/points/order-integration", command);
  await financeHttp.orderPointsIntegration({ storeNo: "ST1", scene: "ONLINE" });
  expect(client.get).toHaveBeenCalledWith("/ops/points/order-integration", { storeNo: "ST1", scene: "ONLINE" });
});
it("sends audited switch commands without client actor or timestamps to the new endpoint", async () => {
  const command = { requestId: "retry-stable", typeCode: "T", scope: "STORE" as const, subjectNo: "S", enabled: false, flow: "EARN" as const, reason: "pause" };
  await financeHttp.changePointsSwitch(command);
  expect(client.post).toHaveBeenCalledWith("/ops/points/switches", command);
  await financeHttp.pointsAgreements({ typeCode: "T", storeNo: "S", after: 12, limit: 20 });
  expect(client.get).toHaveBeenCalledWith("/ops/points/agreements", { typeCode: "T", storeNo: "S", after: 12, limit: 20 });
});
it("keeps maker/checker versions and original withdrawal identifiers on treasury commands", async () => {
  const review = { requestId: "REVIEW", typeCode: "T", requestNo: "ORIGINAL", expectedVersion: 2, approve: false, reason: "unverified" };
  await financeHttp.reviewPointsRequest(review);
  expect(client.post).toHaveBeenCalledWith("/ops/points/request-review", review);
  const execution = { requestId: "EXEC", typeCode: "T", ticketNo: "ORIGINAL_TICKET", reason: "resolve" };
  await financeHttp.executePointsWithdrawal(execution);
  expect(client.post).toHaveBeenCalledWith("/ops/points/withdrawal-execute", execution);
});
it("retains store and revision when operating on one original statement route group", async () => {
  const command = { requestId: "GROUP_A", typeCode: "T", storeNo: "STORE", statementNo: "ORIGINAL_A", revision: 4, action: "CONFIRM" as const, reason: "reviewed" };
  await financeHttp.changePointsStatement(command);
  expect(client.post).toHaveBeenCalledWith("/ops/points/statement-change", command);
  await financeHttp.pointsStatements({ typeCode: "T", storeNo: "STORE", after: 10, limit: 1 });
  expect(client.get).toHaveBeenCalledWith("/ops/points/statements", { typeCode: "T", storeNo: "STORE", after: 10, limit: 1 });
});
it("uses exact account or voucher filters and treats reconciliation as an explicit command", async () => {
  await financeHttp.pointsEntries({ typeCode: "T", holderType: "STORE", holderNo: "STORE", after: 0, limit: 20 });
  expect(client.get).toHaveBeenCalledWith("/ops/points/entries", { typeCode: "T", holderType: "STORE", holderNo: "STORE", after: 0, limit: 20 });
  const command = { requestId: "RECON", typeCode: "T", reason: "review" };
  await financeHttp.reconcilePoints(command);
  expect(client.post).toHaveBeenCalledWith("/ops/points/reconcile", command);
});
