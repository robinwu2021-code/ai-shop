import { describe, expect, it } from "vitest";
import { slugSuggest } from "../src/utils/slug";

/**
 * 门店代码的建议值（V357）。
 *
 * 这些断言盯的是**后端会不会收**：`StoreSlugs.PATTERN` 是
 * `^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$`，所以建议值一旦首尾带连字符、
 * 带大写、超过 32 位，店主点保存才会被拒 —— 而他看到的是一个程序填好的值。
 */
describe("门店代码建议值", () => {
  it("★★★ 中文转拼音，间隔号与空格都当分隔符", () => {
    expect(slugSuggest("虹选鲜果·福田店")).toBe("hong-xuan-xian-guo-fu-tian-dian");
    expect(slugSuggest("张记粮油")).toBe("zhang-ji-liang-you");
    expect(slugSuggest("虹选鲜果 2 号店")).toBe("hong-xuan-xian-guo-2-hao-dian");
  });

  it("★★ 多音字按词取音 —— 「重庆」不是 zhong-qing", () => {
    expect(slugSuggest("重庆小面")).toBe("chong-qing-xiao-mian");
  });

  it("★★ 非中文不拆字母", () => {
    // 逐字拆的话会得到 h-o-n-g-x-u-a-n，那是 nonZh 默认行为
    expect(slugSuggest("Hongxuan Store")).toBe("hongxuan-store");
    expect(slugSuggest("A1 便利店")).toBe("a1-bian-li-dian");
  });

  it("★★★ 产出一定通得过后端的格式判据", () => {
    const pattern = /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/;
    for (const name of [
      "虹选鲜果·福田店", "张记粮油", "Hongxuan Store", "A1 便利店", "重庆小面",
      "虹选生鲜连锁超市深圳福田中心城分店",  // 超长：要截到 32 且不以连字符收尾
      "  空格开头的店  ",
    ]) {
      const s = slugSuggest(name);
      expect(s, `「${name}」的建议值 ${JSON.stringify(s)} 必须通得过后端判据`).toMatch(pattern);
      expect(s.length).toBeLessThanOrEqual(32);
    }
  });

  it("★★ 凑不出 3 位就返回空，而不是给一个会被拒的值", () => {
    expect(slugSuggest("")).toBe("");
    expect(slugSuggest("   ")).toBe("");
    expect(slugSuggest("·")).toBe("");
    expect(slugSuggest("a")).toBe("");
  });
});
