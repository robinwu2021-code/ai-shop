// 策略注册表完整性 + 关键业务规则。
//
// 履约与计价是「交易主干不动、扩展点下沉」的两个扩展点。
// 注册表漏一个枚举，运行到那条履约线才会炸 —— 而那通常是上线后。
import { describe, expect, it } from "vitest";
import { fulfillmentFor } from "@shared/strategies/fulfillment";
import { FULFILLMENT, SETTLE } from "@shared/utils/constants";

describe("履约策略注册表", () => {
  it("每种履约方式都有实现", () => {
    for (const type of Object.values(FULFILLMENT)) {
      const s = fulfillmentFor(type);
      expect(s, `履约方式 ${type} 没有注册策略`).toBeTruthy();
      expect(typeof s.plan).toBe("function");
      expect(typeof s.issueCode).toBe("function");
    }
  });

  it("到店自提与邻里自提是两个不同的策略", () => {
    // 承接方性质不同：门店是入驻商家（收履约服务费），邻居家是用户本人（零报酬）。
    // 合成一个的话，服务费与核销权限都没法分开
    expect(fulfillmentFor(FULFILLMENT.PICKUP)).not.toBe(
      fulfillmentFor(FULFILLMENT.NEIGHBOR_PICKUP),
    );
  });

  it("需要取货码的履约方式出的是 6 位数字码", () => {
    for (const type of [FULFILLMENT.PICKUP, FULFILLMENT.NEIGHBOR_PICKUP]) {
      expect(fulfillmentFor(type).issueCode()).toMatch(/^\d{6}$/);
    }
  });
});

describe("结算参数：占位值也要守住方向", () => {
  it("商家自带客流的佣金率不高于平台客流", () => {
    // ADR-004 §6：他带来的客户在别家的消费才是平台收益，
    // 从这单抽得比平台自己带的还多，方向就反了
    expect(SETTLE.commissionRate.MERCHANT_OWNED).toBeLessThanOrEqual(
      SETTLE.commissionRate.PLATFORM,
    );
  });

  it("履约服务费是正数（邻里自提的零报酬由 PickupPoint 单独保证）", () => {
    expect(SETTLE.fulfillFeePerItemMinor).toBeGreaterThan(0);
  });
});
