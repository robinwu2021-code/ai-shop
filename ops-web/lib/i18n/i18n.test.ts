// i18n 单测。锁两件事：**机制**（插值、回退、方向/Intl tag 登记齐全）与
// **目录质量**（en/zh key 齐平、英文里不许残留汉字）。
// 后者是必要的：缺 key 会静默回退成中文，界面上只是"某几处还是中文"，很难被发现。
import { describe, expect, it } from "vitest";
import { MERCHANT_TRANSITIONS } from "@/lib/types";
import { zh } from "./messages/zh";
import { zhTW } from "./messages/zh-TW";
import { tNav, NAV_OVERLAY_KEYS } from "./nav-labels";
import { NAV_ZH_TW } from "./nav-zh-TW";
import { parseLocale } from "../stores/locale";
import { en } from "./messages/en";
import { DIR, LOCALES, LOCALE_TAG, translate } from "./index";

function keys(obj: Record<string, unknown>, prefix = ""): string[] {
  return Object.entries(obj).flatMap(([k, v]) => {
    const path = prefix ? `${prefix}.${k}` : k;
    return typeof v === "object" && v !== null ? keys(v as Record<string, unknown>, path) : [path];
  });
}

describe("catalog", () => {
  it("registers Simplified Chinese, Traditional Chinese and English", () => expect([...LOCALES]).toEqual(["zh", "zh-TW", "en"]));
  it("Traditional Chinese catalog has complete keys and translated shell labels", () => {
    expect(keys(zhTW).sort()).toEqual(keys(zh).sort());
    expect(translate("zh-TW", "login.password")).toBe("密碼");
    expect(translate("zh-TW", "nav.paymentOps")).toBe("支付運營");
    expect(tNav("经营看板", "zh-TW")).toBe("經營看板");
  });
  it("covers the deployed baseline navigation in Traditional Chinese", () => {
    expect(Object.keys(NAV_ZH_TW).sort()).toEqual([...NAV_OVERLAY_KEYS].sort());
  });
  it("maps cross-app locale query values", () => {
    expect(parseLocale("zh-CN")).toBe("zh");
    expect(parseLocale("zh-TW")).toBe("zh-TW");
    expect(parseLocale("en")).toBe("en");
    expect(parseLocale("evil")).toBeNull();
  });

  it("en 与 zh 的 key **完全齐平** —— 少一条就会在界面上漏出中文", () => {
    // 回退机制会让缺失的 key 静默显示中文，肉眼很难发现，所以要在这里挡住
    expect(keys(en).sort()).toEqual(keys(zh).sort());
  });

  it("en 没有把中文抄过去 —— 复制粘贴漏译是最常见的一种", () => {
    const cjk = keys(en).filter((k) => /[\u4e00-\u9fa5]/.test(translate("en", k)));
    expect(cjk, `这些 key 的英文里还有汉字：\n${cjk.join("\n")}`).toEqual([]);
  });

  it("无空串文案（空串在界面上就是一块看不见的空白）", () => {
    for (const l of LOCALES) for (const k of keys(zh)) expect(translate(l, k), `${l}.${k}`).not.toBe("");
  });

  it("每个 locale 都登记了方向与 Intl tag", () => {
    for (const l of LOCALES) {
      expect(DIR[l]).toBeDefined();
      expect(LOCALE_TAG[l]).toBeDefined();
    }
  });
});

describe("translate 行为", () => {
  it("插值 {n}", () => expect(translate("zh", "common.totalItems", { n: 5 })).toBe("共 5 条"));
  it("缺参数时保留占位符（而不是渲染成 undefined）", () =>
    expect(translate("zh", "common.totalItems")).toBe("共 {n} 条"));
  it("未知 key 原样返回，便于一眼看出漏配", () =>
    expect(translate("zh", "no.such.key")).toBe("no.such.key"));
  it("枚举文案齐全：状态机的每个值都有文案", () => {
    /*
     * 取值直接来自类型定义，不再手抄一份 —— 手抄的那份此前还停在旧模型上
     * （DRAFT/SUBMITTED/REVIEWING/APPROVED…那是**审核状态**，已经搬到申请单上了），
     * 于是模型改完之后，这条守卫守的是一个不存在的枚举。
     */
    for (const s of Object.keys(MERCHANT_TRANSITIONS)) {
      expect(translate("zh", `merchantStatus.${s}`)).not.toBe(`merchantStatus.${s}`);
      expect(translate("en", `merchantStatus.${s}`)).not.toBe(`merchantStatus.${s}`);
    }
  });
});
