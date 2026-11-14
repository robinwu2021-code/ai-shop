import { describe, expect, it } from "vitest";
import { DICTS, isRtl, pickLang, t } from "./i18n";

describe("文案", () => {
  it("★★★ 四种语言键集一致（漏一条就会在那种语言里显示键名）", () => {
    const base = Object.keys(DICTS["zh-CN"]).sort();
    for (const [lang, dict] of Object.entries(DICTS)) {
      expect(Object.keys(dict).sort(), lang).toEqual(base);
    }
  });

  it("★★ 占位符四种语言一致", () => {
    for (const key of Object.keys(DICTS["zh-CN"]) as (keyof (typeof DICTS)["zh-CN"])[]) {
      const want = (DICTS["zh-CN"][key].match(/\{\w+\}/g) ?? []).sort();
      for (const [lang, dict] of Object.entries(DICTS)) {
        expect((dict[key].match(/\{\w+\}/g) ?? []).sort(), `${lang} ${key}`).toEqual(want);
      }
    }
  });

  it("★★ 选语言：浏览器语言优先，对不上按市场，再不行英文；阿拉伯语从右到左", () => {
    expect(pickLang(["zh-TW"], "CN")).toBe("zh-TW");
    expect(pickLang(["zh-Hant-HK"], null)).toBe("zh-TW");
    expect(pickLang(["fr-FR"], "AE")).toBe("ar");
    expect(pickLang(["fr-FR"], "TW")).toBe("zh-TW");
    expect(pickLang(["fr-FR"], null)).toBe("en");
    expect(isRtl("ar")).toBe(true);
    expect(t("en", "pay.confirm", { amount: "$1.00" })).toBe("Pay $1.00");
  });
});
