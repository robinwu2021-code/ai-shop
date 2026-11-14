import { describe, expect, it, vi } from "vitest";
const get = vi.hoisted(() => vi.fn());
vi.mock("@shared/net/http-client", () => ({ clientTag: () => "MP", http: { get, post: vi.fn(), put: vi.fn() } }));
import { httpApi } from "@/api/http";
describe("本人储值HTTP合同", () => {
  it("只发门店、筛选与不透明游标，不指定用户身份", async () => {
    await httpApi.storedValueStores({ size: 20, cursor: "opaque" });
    expect(get).toHaveBeenLastCalledWith("/mp/stored-value/stores", { size: 20, cursor: "opaque" }, undefined);
    await httpApi.storedValueRecords("STORE/A", { type: "REDEMPTION", startDate: "2026-10-01", focusDocumentNo: "EU1" });
    expect(get).toHaveBeenLastCalledWith("/mp/stored-value/store/STORE%2FA/records", { type: "REDEMPTION", startDate: "2026-10-01", focusDocumentNo: "EU1" }, undefined);
    await httpApi.storedValueNotificationCapability();
    expect(get).toHaveBeenLastCalledWith("/mp/stored-value/notification-capability", undefined, undefined);
  });
});
