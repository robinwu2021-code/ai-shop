import { expect, it, vi } from "vitest";
const client = vi.hoisted(() => ({ get: vi.fn().mockResolvedValue({}), post: vi.fn().mockResolvedValue({}) }));
vi.mock("./http-client", () => ({ client }));
import { financeHttp } from "./https/finance";
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
