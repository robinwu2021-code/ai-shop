import { describe, expect, it } from "vitest";
import { parseAutomationTicket } from "../src/shared/automation-ticket";

/** 启动参数里的票据（ADR-027）。认不出来就当没有 —— 猜错的代价是拿垃圾去换会话、白白被记一次失败审计。 */
describe("启动参数里的密钥票据", () => {
  const T = "v1.eyJyZWFsbSI6IkIifQ.c2lnbmF0dXJl";

  it("三种形态都认：票据本身、JSON、查询串", () => {
    expect(parseAutomationTicket(T)).toBe(T);
    expect(parseAutomationTicket(` ${T}\n`)).toBe(T);
    expect(parseAutomationTicket(JSON.stringify({ automationTicket: T }))).toBe(T);
    expect(parseAutomationTicket(`a=1&automationTicket=${encodeURIComponent(T)}`)).toBe(T);
  });

  it("不像票据的一律不认", () => {
    expect(parseAutomationTicket("")).toBeNull();
    expect(parseAutomationTicket(undefined)).toBeNull();
    expect(parseAutomationTicket("hello")).toBeNull();
    expect(parseAutomationTicket(JSON.stringify({ automationTicket: "not a ticket" }))).toBeNull();
    expect(parseAutomationTicket("{broken json")).toBeNull();
    expect(parseAutomationTicket("v1.only-two")).toBeNull();
  });
});
