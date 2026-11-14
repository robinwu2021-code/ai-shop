import { describe, expect, it } from "vitest";
import { PAYMENT_WORKSPACES, paymentWorkspaceHref } from "./ops-bridge";

describe("live payment workspace navigation", () => {
  it("opens each actual live payment route under the configured same-origin base", () => {
    const paths = { channels: "/ops/channels", merchants: "/merchants", onboarding: "/merchants/onboarding-review", payments: "/trade/payments", "payment-exceptions": "/trade/exceptions" } as const;
    for (const key of Object.keys(paths) as (keyof typeof paths)[]) {
      expect(paymentWorkspaceHref("https://ops.test", "zh-TW", key, "/pay-admin/")).toBe(`/pay-admin${paths[key]}/?locale=zh-TW`);
    }
  });
  it("does not redirect a shared session to an external origin", () => {
    expect(paymentWorkspaceHref("https://ops.test", "zh", "onboarding", "https://evil.test/collect")).toBe("/oms/merchants/onboarding-review/?locale=zh-CN");
  });
  it("uses the payment controllers' actual view permissions", () => {
    expect(Object.values(PAYMENT_WORKSPACES).map(v => v.permission)).toEqual(["pay:channel:view", "pay:merchant:view", "pay:onboarding:view", "pay:order:view", "pay:order:view"]);
  });
});
