/**
 * `cn()` 必须把七档字阶当成**一个冲突组**。
 *
 * 不这么登记的话，组件自带的档位与调用点传进来的档位会同时留在 class 上 ——
 * 两个类都设 font-size、都是单类选择器，谁生效只由 globals.css 里的先后决定。
 * 2026-09-09 真实页面体检在 `/jobs` 上一次报出 12 处（`HelpNote` base 带 txt-body、
 * 调用点传 txt-caption），当时是 caption 赢，**纯属它在 globals.css 里写在后面**。
 */
import { describe, it, expect } from "vitest";
import { cn } from "./utils";

describe("cn 对字阶的合并", () => {
  it("后者覆盖前者，只留一个档", () => {
    expect(cn("txt-body", "txt-caption")).toBe("txt-caption");
    expect(cn("txt-caption", "txt-title")).toBe("txt-title");
  });

  it("与 Tailwind 自己的字号类互相覆盖（同属 font-size 组）", () => {
    expect(cn("txt-body", "text-lg")).toBe("text-lg");
    expect(cn("text-lg", "txt-body")).toBe("txt-body");
  });

  it("不误伤别的类 —— 字重、颜色、间距都该留着", () => {
    expect(cn("txt-body font-medium text-muted-foreground", "txt-caption"))
      .toBe("font-medium text-muted-foreground txt-caption");
  });
});
