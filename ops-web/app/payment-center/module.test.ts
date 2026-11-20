import { describe, expect, it } from "vitest";
import { LEGACY_MENU_HREFS, normalizeLegacyPaymentMenu } from "./module";
import type { MenuFunction } from "@/lib/types";

describe("库里的旧支付菜单地址（V686 未执行前）", () => {
  it("5 个旧地址映射到支付中心页签；别的点位原样", () => {
    const fns = [
      { functionCode: "OPS_MERCHANT", name: "商户", href: "/merchants", points: [
        { pointCode: "OPS_MERCHANT__TAB_PAYMENT", href: "/merchants?tab=payment", name: "商户列表", pointType: "MENU" },
        { pointCode: "OPS_MERCHANT__TAB_LIST", href: "/merchants?tab=list", name: "商家档案", pointType: "MENU" },
      ] },
      { functionCode: "OPS_ORDER", name: "订单", href: "/orders", points: [
        { pointCode: "OPS_ORDER__TAB_PAYMENTS", href: "/orders?tab=payments", name: "支付订单", pointType: "MENU" },
      ] },
    ] as unknown as MenuFunction[];
    const out = normalizeLegacyPaymentMenu(fns);
    expect(out.flatMap((f) => (f.points ?? []).map((p) => p.href))).toEqual(["/payment-center?tab=merchants", "/merchants?tab=list", "/payment-center?tab=orders"]);
    expect(fns[0].points![0].href).toBe("/merchants?tab=payment");
  });

  it("映射表覆盖 V671 的 5 个支付入口", () => {
    expect(LEGACY_MENU_HREFS).toEqual({
      "/merchants?tab=payment": "/payment-center?tab=merchants",
      "/merchants?tab=payment-onboarding": "/payment-center?tab=onboardings",
      "/merchants?tab=payment-channels": "/payment-center?tab=channels",
      "/orders?tab=payments": "/payment-center?tab=orders",
      "/orders?tab=payment-exceptions": "/payment-center?tab=exceptions",
    });
  });
});
