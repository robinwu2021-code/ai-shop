import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { ENDPOINTS } from "@/api/endpoints";

const page = readFileSync(resolve(__dirname,"../src/pages/pay/index.vue"), "utf8");

describe("积分报价变化需要买家重新确认", () => {
  it("只读本人订单的阶段，并阻止按旧金额支付", () => {
    expect(ENDPOINTS.orderPointsState.path).toBe("/mp/order/:orderNo/points-state");
    expect(page).toContain("api.orderPointsState(payNo)");
    expect(page).toContain('detail.status === "POINTS_PROCESSING"');
    expect(page).toContain('pointsState.value?.phase === "RECONFIRM"');
  });

  it("取消原单后清除幂等键，再回购物车重新确认", () => {
    expect(page).toContain("async function cancelForReconfirm()");
    expect(page).toContain("clearCheckoutKey()");
    expect(page).toContain("uni.switchTab({ url: ROUTES.cart })");
  });
});
