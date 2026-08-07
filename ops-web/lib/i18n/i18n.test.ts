// i18n 单测。锁两件事：**机制**（插值、回退、方向/Intl tag 登记齐全）与
// **目录质量**（en/zh key 齐平、英文里不许残留汉字）。
// 后者是必要的：缺 key 会静默回退成中文，界面上只是"某几处还是中文"，很难被发现。
import { describe, expect, it } from "vitest";
import { zh } from "./messages/zh";
import { en } from "./messages/en";
import { DIR, LOCALES, LOCALE_TAG, translate } from "./index";

function keys(obj: Record<string, unknown>, prefix = ""): string[] {
  return Object.entries(obj).flatMap(([k, v]) => {
    const path = prefix ? `${prefix}.${k}` : k;
    return typeof v === "object" && v !== null ? keys(v as Record<string, unknown>, path) : [path];
  });
}

describe("catalog", () => {
  it("中 / EN 两种界面语言", () => expect([...LOCALES]).toEqual(["zh", "en"]));

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
    for (const s of ["DRAFT", "SUBMITTED", "REVIEWING", "APPROVED", "REJECTED", "SUSPENDED"]) {
      expect(translate("zh", `merchantStatus.${s}`)).not.toBe(`merchantStatus.${s}`);
    }
  });
});
