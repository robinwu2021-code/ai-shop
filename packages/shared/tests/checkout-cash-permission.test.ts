import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const root = join(import.meta.dirname, "../../..");
const read = (path: string) => readFileSync(join(root, path), "utf8");
const java = read("backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java");
const permission = "pay:cash:receive";

// The older biz: label guard deliberately cannot discover this independent pay: permission.
describe("explicit offline receipt authority", () => {
  it("has one cash capability and a matching Chinese owner label", () => {
    expect(java).toContain(`CASH_RECEIVE = "${permission}"`);
    expect(java).toContain('Map.entry(CASH_RECEIVE, "确认线下实收")');
  });

  it.each([
    ["zh-CN", "确认线下实收"],
    ["en", "Confirm offline receipt"],
    ["ar", "تأكيد استلام الدفع دون اتصال"],
  ])("is selectable with the exact %s permission label", (locale, label) => {
    expect(read(`b-app/src/i18n/locale/${locale}.ts`)).toContain(`"${permission}": "${label}"`);
  });

  it("is listed by the shared permission source without granting legacy roles cash access", () => {
    const mock = read("packages/shared/src/mock/db.ts");
    expect(mock).toContain(`"${permission}": "确认线下实收"`);
    const start = java.indexOf("ROLE_PERMS =");
    expect(start).toBeGreaterThan(0);
    const roles = java.slice(start, java.indexOf(");", start));
    expect(roles).not.toContain("CASH_RECEIVE");
    expect(roles).not.toContain(permission);
  });
});


describe("manual service declaration authority", () => {
  it("has only the consumed M1 confirm capability and its owner label", () => {
    expect(java).toContain('MANUAL_CONFIRM = "trade:manual-service:confirm"');
    expect(java).toContain('Map.entry(MANUAL_CONFIRM, "人工确认服务收款")');
    const start = java.indexOf("ROLE_PERMS =");
    expect(java.slice(start, java.indexOf(");", start))).not.toContain("MANUAL_CONFIRM");
  });
  it.each([
    ["zh-CN", "人工确认服务收款"],
    ["en", "Confirm manual service receipt"],
    ["ar", "تأكيد استلام مقابل الخدمة يدويًا"],
  ])("has the exact %s label", (locale, label) => {
    expect(read(`b-app/src/i18n/locale/${locale}.ts`)).toContain(`"trade:manual-service:confirm": "${label}"`);
  });
});
