import { describe, expect, it } from "vitest";
import { storedValueMock } from "@/api/mocks/stored-value";
describe("储值mock遵循门店当地自然日", () => {
  it("中国门店跨UTC日期的储值在门店20日筛选中保留", async () => {
    const page = await storedValueMock.storedValueRecords("STORE_SV_DEMO", { startDate: "2026-10-20", endDate: "2026-10-20" });
    expect(page.records.map(record => record.documentNo)).toEqual(["PR20261020000001"]);
    expect(page.filter).toEqual({ type: "ALL", startDate: "2026-10-20", endDate: "2026-10-20" });
    expect((await storedValueMock.storedValueNotificationCapability()).available).toBe(false);
  });
});
