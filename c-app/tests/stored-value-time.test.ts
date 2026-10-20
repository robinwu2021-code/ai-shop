import { afterEach, describe, expect, it, vi } from "vitest";
import { setCurrentOffset } from "@shared/utils/datetime";
import { storedValueDateTime, storedValueDirectoryTime } from "@/shared/stored-value-time";
afterEach(() => { setCurrentOffset(480); vi.unstubAllGlobals(); });
describe("储值门店自然日时间", () => {
  it("用户在海湾市场仍按中国门店日期显示跨日账单", () => {
    setCurrentOffset(240);
    expect(storedValueDateTime("2026-10-20T16:30:00Z", "Asia/Shanghai")).toBe("2026-10-21 00:30 (Asia/Shanghai)");
    expect(storedValueDirectoryTime("2026-10-20T16:30:00Z")).toBe("2026-10-20 20:30 (UTC+04:00)");
  });
  it("IANA时区随夏令时切换，不使用固定24小时偏移", () => {
    expect(storedValueDateTime("2026-07-01T04:30:00Z", "America/New_York")).toBe("2026-07-01 00:30 (America/New_York)");
    expect(storedValueDateTime("2026-01-01T04:30:00Z", "America/New_York")).toBe("2025-12-31 23:30 (America/New_York)");
  });
  it("无Intl的小程序仍正确展示已支持门店时区，未知时区明确显示UTC", () => {
    vi.stubGlobal("Intl", undefined);
    expect(storedValueDateTime("2026-10-20T16:30:00Z", "Asia/Shanghai")).toBe("2026-10-21 00:30 (Asia/Shanghai)");
    expect(storedValueDateTime("2026-10-20T16:30:00Z", "Asia/Dubai")).toBe("2026-10-20 20:30 (Asia/Dubai)");
    expect(storedValueDateTime("2026-07-01T04:30:00Z", "America/New_York")).toBe("2026-07-01 04:30 (UTC)");
  });
});
