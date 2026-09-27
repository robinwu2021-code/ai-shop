import { describe, expect, it } from "vitest";
import { mergeI18nText } from "../src/shared/i18n-text";

const EMPTY = { "zh-CN": "", en: "", ar: "" };

describe("编辑页回显三语原文", () => {
  it("后端给 {}（没有三语）：当前语言用拍平的 title 补上 —— 此前这里回显成空", () => {
    expect(mergeI18nText(EMPTY, {}, "zh-CN", "五得利 八星雪花小麦粉"))
      .toEqual({ "zh-CN": "五得利 八星雪花小麦粉", en: "", ar: "" });
  });
  it("三语齐全：整份照搬，不被拍平的那份覆盖", () => {
    expect(mergeI18nText(EMPTY, { "zh-CN": "香梨", en: "Pear", ar: "" }, "zh-CN", "别的"))
      .toEqual({ "zh-CN": "香梨", en: "Pear", ar: "" });
  });
  it("null 与缺格：同样回落，其余语言保持原样", () => {
    expect(mergeI18nText(EMPTY, null, "en", "Lemon")).toEqual({ "zh-CN": "", en: "Lemon", ar: "" });
    expect(mergeI18nText(EMPTY, { en: "Lemon" }, "zh-CN", "柠檬"))
      .toEqual({ "zh-CN": "柠檬", en: "Lemon", ar: "" });
  });
});
