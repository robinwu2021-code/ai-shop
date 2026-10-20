import { expect, it, vi } from "vitest";
const client = vi.hoisted(() => ({ get: vi.fn().mockResolvedValue({}) }));
vi.mock("./http-client", () => ({ client }));
import { financeHttp } from "./https/finance";

it("queries cursor-paged member accounts using the deployed holder contract", async () => {
  const query = { typeCode: "LOCAL", holderType: "USER" as const, holderNo: "U100", after: 20, limit: 50 };
  await financeHttp.pointsAccounts(query);
  expect(client.get).toHaveBeenCalledWith("/ops/points/accounts", query);
});

it("filters issue and redemption records before cursor pagination", async () => {
  const query = { typeCode: "LOCAL", direction: "EARN" as const, from: "2026-10-01", to: "2026-10-20", storeNo: "S1", userNo: "U1", after: 40, limit: 50 };
  await financeHttp.pointsTransactions(query);
  expect(client.get).toHaveBeenCalledWith("/ops/points/transactions", query);
});

it("reads transaction detail by escaped voucher number", async () => {
  await financeHttp.pointsTransaction("LOCAL", "V/1");
  expect(client.get).toHaveBeenCalledWith("/ops/points/transactions/V%2F1", { typeCode: "LOCAL" });
});

it("reads bounded Shanghai period statistics without client-side aggregation", async () => {
  const query = { typeCode: "LOCAL", from: "2026-10-01", to: "2026-10-20" };
  await financeHttp.pointsPeriodStats(query);
  expect(client.get).toHaveBeenCalledWith("/ops/points/stats-period", query);
});
