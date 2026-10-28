import { expect, it, vi } from "vitest";
const client = vi.hoisted(() => ({ get: vi.fn().mockResolvedValue({}), post: vi.fn().mockResolvedValue({}) }));
vi.mock("./http-client", () => ({ client }));
import { financeHttp } from "./https/finance";
import { systemHttp } from "./https/system";
it("preserves account, transaction and period filters and encodes the original voucher", async () => {
  const q = { typeCode: "T", holderType: "USER" as const, after: 20, limit: 50 };
  await financeHttp.pointsAccounts(q);
  expect(client.get).toHaveBeenCalledWith("/ops/points/accounts", q);
  const period = { typeCode: "T", from: "2026-10-01", to: "2026-10-28" };
  await financeHttp.pointsTransactions({ ...period, direction: "REDEEM", storeNo: "ST1" });
  expect(client.get).toHaveBeenCalledWith("/ops/points/transactions", { ...period, direction: "REDEEM", storeNo: "ST1" });
  await financeHttp.pointsTransaction("T", "V/1");
  expect(client.get).toHaveBeenCalledWith("/ops/points/transactions/V%2F1", { typeCode: "T" });
  await financeHttp.pointsPeriodStats(period);
  expect(client.get).toHaveBeenCalledWith("/ops/points/stats-period", period);
});
it("keeps print scope on each read and sends reconciliation idempotency as a header", async () => {
  const scope = { tenantNo: "TEN", entityNo: "ENT", storeNo: "ST" };
  await systemHttp.printHealth(scope);
  expect(client.get).toHaveBeenCalledWith("/ops/print/health", scope);
  await systemHttp.printIncidents({ ...scope, page: 2, size: 20 });
  expect(client.get).toHaveBeenCalledWith("/ops/print/incidents", { ...scope, page: 2, size: 20 });
  await systemHttp.printOperation("OP/1", scope);
  expect(client.get).toHaveBeenCalledWith("/ops/print/operations/OP%2F1", scope);
  const command = { ...scope, sourceKind: "SUB_ORDER", from: "2026-10-01T00:00:00Z", to: "2026-10-02T00:00:00Z", dryRun: true, reason: "review" };
  await systemHttp.reconcilePrint(command, "RETRY-1");
  expect(client.post).toHaveBeenCalledWith("/ops/print/reconciliations", command, undefined, { "Idempotency-Key": "RETRY-1" });
});
