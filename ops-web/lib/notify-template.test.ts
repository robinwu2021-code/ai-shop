import { describe, expect, it } from "vitest";
import { isFreeText, placeholdersOf, renderTemplate } from "./notify-template";

describe("模板预览", () => {
  it("代入后就是发出去的样子", () => {
    expect(renderTemplate("【数智邻购】您的验证码是 {code}，5 分钟内有效", { code: "328104" }))
      .toBe("【数智邻购】您的验证码是 328104，5 分钟内有效");
  });

  it("★ 没填的占位保持原样，不替换成空", () => {
    // 替换成空会让预览读起来像一句通顺的话，而实际发出去中间少一块 ——
    // 运营看着「验证码是 ，5 分钟内有效」才知道自己漏填了
    expect(renderTemplate("验证码是 {code}，{tip}", { code: "1234" }))
      .toBe("验证码是 1234，{tip}");
    expect(renderTemplate("验证码是 {code}", { code: "" })).toBe("验证码是 {code}");
  });

  it("取出占位名，按出现顺序且去重", () => {
    expect(placeholdersOf("您有 {number1} 件包裹 · {thing2}")).toEqual(["number1", "thing2"]);
    expect(placeholdersOf("{a} 与 {a} 与 {b}")).toEqual(["a", "b"]);
    expect(placeholdersOf("没有占位")).toEqual([]);
  });

  it("★★ 短信与微信的正文不可整段改 —— 通道方只收已报备的模板", () => {
    // 这条不是我们的选择：阿里云收 TemplateCode+TemplateParam，
    // 微信收 template_id+data，自由文本会被直接拒。
    // 界面上给自由输入框等于让运营填一段永远发不出去的话
    expect(isFreeText("SMS")).toBe(false);
    expect(isFreeText("WXSUB")).toBe(false);
    expect(isFreeText("MAIL")).toBe(true);
    expect(isFreeText("PUSH")).toBe(true);
    expect(isFreeText("INAPP")).toBe(true);
  });
});
