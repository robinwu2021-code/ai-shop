import { describe, expect, it } from "vitest";
import { pointsText } from "./points-i18n";
import { POINTS_ZH_TW_TEMPLATES } from "./i18n/points-zh-TW";
describe("points display language", () => {
  it("uses static Traditional labels and retains existing Simplified and English behavior", () => {
    expect(pointsText("暂无调账记录", "No adjustment requests", "zh-TW")).toBe("暫無調賬記錄");
    expect(pointsText("暂无调账记录", "No adjustment requests", "zh")).toBe("暂无调账记录");
    expect(pointsText("暂无调账记录", "No adjustment requests", "en")).toBe("No adjustment requests");
  });
  it("preserves captured business identifiers and unknown business values", () => {
    for (const [source, translated] of POINTS_ZH_TW_TEMPLATES) {
      const fill = (s: string) => s.replace(/\{p\d+\}/g, key => `商品编号-${key.slice(1, -1)}`);
      expect(pointsText(fill(source), "unused", "zh-TW")).toBe(fill(translated));
    }
    expect(pointsText("商户自定义积分编号-ID123", "unused", "zh-TW")).toBe("商户自定义积分编号-ID123");
  });
});
