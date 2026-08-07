// 系统配置规则测试（P-17.1）。这一页配的东西大多直接作用在 C 端，所以校验都不能松。
import { beforeEach, describe, expect, it } from "vitest";
import { systemMock } from "@/lib/api/mocks/system";
import { C_END_THEMES, FULL_SCHEME_THEMES } from "@/lib/stores/theme";
import { BASE_CURRENCY } from "@/lib/types";
import { appearance, featureFlags, markets, ruleTexts } from "./system";

const A0 = { ...appearance };
const M0 = JSON.parse(JSON.stringify(markets)) as typeof markets;
const R0 = { ...ruleTexts };
const F0 = JSON.parse(JSON.stringify(featureFlags)) as typeof featureFlags;
beforeEach(() => {
  Object.assign(appearance, A0);
  markets.length = 0; markets.push(...(JSON.parse(JSON.stringify(M0)) as typeof markets));
  Object.assign(ruleTexts, R0);
  featureFlags.length = 0; featureFlags.push(...(JSON.parse(JSON.stringify(F0)) as typeof featureFlags));
});

describe("皮肤下发（P-17.1.1）", () => {
  it("取值必须是可下发皮肤之一 —— C 端拿到不认识的皮肤名会静默回落，表现为「配了没生效」", async () => {
    await expect(
      // @ts-expect-error 故意传非法皮肤名，验运行时也挡
      systemMock.saveAppearance({ defaultSkin: "rainbow", fallbackLang: "zh" }),
    ).rejects.toThrow(/必须是/);
  });

  it("C 端有的皮肤都能下发（与 C 端 SKINS 同源）", async () => {
    for (const t of C_END_THEMES) {
      const a = await systemMock.saveAppearance({ defaultSkin: t.key, fallbackLang: "zh" });
      expect(a.defaultSkin).toBe(t.key);
    }
  });

  it("运营端专有皮肤不能下发给 C 端 —— C 端没有 business，下发过去只会静默回落", async () => {
    for (const k of FULL_SCHEME_THEMES) {
      await expect(
        systemMock.saveAppearance({ defaultSkin: k, fallbackLang: "zh" }),
      ).rejects.toThrow(/必须是/);
    }
  });

  it("节日皮肤的结束时间必须晚于开始", async () => {
    await expect(
      systemMock.saveAppearance({
        defaultSkin: "fresh", festivalSkin: "promo",
        festivalFrom: "2026-10-01T00:00:00Z", festivalTo: "2026-09-01T00:00:00Z", fallbackLang: "zh",
      }),
    ).rejects.toThrow(/晚于开始/);
  });
});

describe("市场与汇率（P-17.1.3）", () => {
  it(`${BASE_CURRENCY} 是基准货币，汇率不可改 —— 改了整套价格换算的原点就没了`, async () => {
    await expect(systemMock.saveMarketRate("CN", 1.2, true)).rejects.toThrow(/基准货币/);
  });

  it("非基准货币汇率必须大于 0", async () => {
    await expect(systemMock.saveMarketRate("SG", 0, true)).rejects.toThrow(/大于 0/);
  });

  it("合法汇率落库", async () => {
    const m = await systemMock.saveMarketRate("SG", 5.4, true);
    expect(m.rate).toBe(5.4);
  });
});

describe("规则文案（P-17.1.4）", () => {
  it("三条都不能为空 —— C 端要展示给用户看", async () => {
    await expect(
      systemMock.saveRuleTexts({ refund: "", pickup: "x", weighDiff: "y" }),
    ).rejects.toThrow(/refund/);
    await expect(
      systemMock.saveRuleTexts({ refund: "a", pickup: " ", weighDiff: "" }),
    ).rejects.toThrow(/pickup|weighDiff/);
  });

  it("合法文案落库并留痕", async () => {
    const r = await systemMock.saveRuleTexts({ refund: "A", pickup: "B", weighDiff: "C" });
    expect(r.refund).toBe("A");
    expect(r.updatedBy).toBeTruthy();
  });
});

describe("开关与灰度（P-17.1.5）", () => {
  it("灰度比例需在 0–100", async () => {
    await expect(systemMock.saveFeatureFlag("group_demand", true, 120)).rejects.toThrow(/0–100/);
    await expect(systemMock.saveFeatureFlag("group_demand", true, -1)).rejects.toThrow(/0–100/);
  });

  it("保存开关与比例", async () => {
    const f = await systemMock.saveFeatureFlag("group_demand", false, 10);
    expect(f).toMatchObject({ enabled: false, rolloutPercent: 10 });
  });
});
