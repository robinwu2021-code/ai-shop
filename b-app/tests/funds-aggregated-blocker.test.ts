import { describe, expect, it, beforeEach } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useMerchantStore } from "@/stores/merchant";
import type { MerchantProfile } from "@shared/types";

/**
 * 「还不能收款」这条告警对**归集商户**不成立。
 *
 * <p>归集下买家付的是平台的收款号，平台再结算给商家 —— 他压根不需要自己的
 * 二级商户号。而那条告警读的是通道进件状态（`payments.some(p => p.canReceiveMoney)`），
 * 与资金路径无关，于是它对归集商户**永远亮着、也永远点不掉**：
 * 点进去是一份跟他没关系的进件表单。
 *
 * <p>后端那边是没有这道闸的：`MerchantBrief.canReceive` 就是 `status === "ACTIVE"`，
 * 下单链路上也没有任何一处拿进件当闸门。也就是说他**本来就能做生意**，
 * 只是工作台一直在说他不能 —— 这类「说的和做的相反」正是这一屏最贵的失败。
 *
 * <p>判据放在 store 的 getter 上而不是页面里：页面那一行是
 * `can("biz:finance") && !fundsAggregated && !canReceive`，三个条件里
 * 只有这一个是新加的，也只有它能单独测。
 */
describe("归集商户不该看到「还不能收款」", () => {
  beforeEach(() => setActivePinia(createPinia()));

  const withFundsMode = (fundsMode?: string) => {
    const m = useMerchantStore();
    m.profile = { status: "ACTIVE", fundsMode } as unknown as MerchantProfile;
    return m;
  };

  it("★★★ 归集（AGGREGATED）→ 不提示", () => {
    expect(withFundsMode("AGGREGATED").fundsAggregated).toBe(true);
  });

  it("★★★ 直连（DIRECT）→ 仍要提示 —— 钱进的是他自己的户，没进件就是真收不到", () => {
    expect(withFundsMode("DIRECT").fundsAggregated).toBe(false);
  });

  it("★★ 字段缺失按归集 —— 与后端 MerchantVO.Brief 同口径，且猜错的代价不对称", () => {
    // 少提示一条告警他照常做生意；多提示一条，他会去补一份根本用不上的资料
    expect(withFundsMode(undefined).fundsAggregated).toBe(true);
  });

  it("★★ profile 还没拉回来时也按归集 —— 冷启那一瞬间不该先闪一条红字", () => {
    const m = useMerchantStore();
    m.profile = null;
    expect(m.fundsAggregated).toBe(true);
  });
});
