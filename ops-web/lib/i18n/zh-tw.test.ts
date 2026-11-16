// 繁体呈现（TDD-运营端页面文案繁体）：页面正文由简体源串经 opencc（cn → twp）转换，
// 再叠台湾运营用语修正。这里锁住转换函数本身；各页面的覆盖率在 page-copy.test.ts。
import { describe, expect, it } from "vitest";
import { toTraditional, traditionalCopy } from "./zh-tw";
import { tNav } from "./nav-labels";
import { translate } from "./index";

describe("toTraditional", () => {
  it("转成台湾繁体", () => {
    expect(toTraditional("入驻管理")).toBe("入駐管理");
    expect(toTraditional("服务协议待商户本人确认")).toBe("服務協議待商戶本人確認");
  });

  it("占位符与 ASCII 原样", () => {
    expect(toTraditional("共 {n} 条，商户 {merchantNo}")).toBe("共 {n} 條，商戶 {merchantNo}");
    expect(toTraditional("APPROVED 仅表示资料通过")).toBe("APPROVED 僅表示資料通過");
  });

  it("台湾运营用语修正", () => {
    expect(toTraditional("审核中")).toBe("審核中");
    expect(toTraditional("店主已注册邮箱")).toBe("店主已註冊信箱");
    expect(toTraditional("账号与账户")).toBe("帳號與帳戶");
    expect(toTraditional("登录已过期")).toBe("登入已過期");
    expect(toTraditional("全部类型")).toBe("全部類型");
  });

  it("手写繁体译文同样做用语修正", () => {
    // messages/zh-TW.ts 原文是「使用其他賬號登入」
    expect(translate("zh-TW", "login.otherAccount")).toBe("使用其他帳號登入");
  });
});

describe("traditionalCopy", () => {
  const dict = { title: "入驻管理", count: "共 {n} 条" };

  it("局部覆盖优先，其余仍为转换结果", () => {
    const tw = traditionalCopy(dict, { title: "入駐審核" });
    expect(tw).toEqual({ title: "入駐審核", count: "共 {n} 條" });
  });

  it("整表转换按引用缓存", () => {
    expect(traditionalCopy(dict)).toBe(traditionalCopy(dict));
  });
});

describe("繁体兜底", () => {
  it("tNav：繁体表里没有的标签也转成繁体", () => {
    // twp 词表把「菜单」换成台湾惯用的「選單」
    expect(tNav("测试专用不存在的菜单", "zh-TW")).toBe("測試專用不存在的選單");
  });

  it("translate：繁体目录缺的 key 用简体转换", () => {
    expect(translate("zh-TW", "不存在的键")).toBe("不存在的鍵");
  });

  it("简体、英文不受影响", () => {
    expect(tNav("测试专用不存在的菜单", "zh")).toBe("测试专用不存在的菜单");
    expect(translate("en", "不存在的键")).toBe("不存在的键");
  });
});
