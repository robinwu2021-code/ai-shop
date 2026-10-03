/**
 * 标识的几何是「可复现」的核心承诺：同一组参数生成全部形态。
 *
 * 这里断言的是**同源**，不是像素：页头的标识必须与 `brand/logo/mark-red.svg`
 * 逐路径一致。上一版把路径抄进组件，品牌换代（扁弧母题）之后官网页头还在画旧圆弧、
 * favicon 已经是新的 —— 同一个页面上两套标识，而没有任何东西会报错。
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Mark, Logo } from "./logo";

const MARK = readFileSync(join(process.cwd(), "..", "brand", "logo", "mark-red.svg"), "utf8");

/** 从任意 svg 文本里取路径的 `d`，用于逐条比对 */
const paths = (svg: string) => [...svg.matchAll(/\sd="([^"]+)"/g)].map((m) => m[1]);

describe("Mark 与品牌产物同源", () => {
  it("弧线与 H/X 路径逐条等于 brand/logo/mark-red.svg", () => {
    const { container } = render(<Mark />);
    expect(paths(container.innerHTML)).toEqual(paths(MARK));
  });

  it("弧线（虹）是扁的，不是正圆 —— 母题参数 arc_flat 0.65", () => {
    const arc = paths(MARK)[0]!;
    const m = arc.match(/A([\d.]+)\s+([\d.]+)/);
    expect(m, "第一条路径应是弧线").toBeTruthy();
    const [rx, ry] = [Number(m![1]), Number(m![2])];
    expect(ry / rx).toBeCloseTo(0.65, 2);
  });

  it("方章圆角 = 0.275 × 边长（64 → 17.6）", () => {
    const { container } = render(<Mark />);
    expect(container.querySelector("rect")?.getAttribute("rx")).toBe("17.6");
  });

  it.each([
    ["brand", "#e1251b", "#ffffff"],
    ["reverse", "#ffffff", "#e1251b"],
    ["ink", "#17181a", "#ffffff"],
  ] as const)("%s 档：底 %s / 字 %s", (tone, plate, glyph) => {
    const { container } = render(<Mark tone={tone} />);
    expect(container.querySelector("rect")?.getAttribute("fill")).toBe(plate);
    // 字色替换到位：产物里的品牌红不该残留在反白稿里
    expect(container.innerHTML.toLowerCase()).toContain(glyph);
  });

  it("尺寸等比，不改 viewBox（否则几何比例会随尺寸漂）", () => {
    const { container } = render(<Mark size={28} />);
    const svg = container.querySelector("svg")!;
    expect(svg.getAttribute("viewBox")).toBe("0 0 64 64");
    expect(svg.getAttribute("width")).toBe("28");
  });
});

describe("Logo 字标", () => {
  it("中文名与英文名都在，且 HX MALL 是全大写", () => {
    const { getByText } = render(<Logo />);
    expect(getByText("虹选 · 好物")).toBeTruthy();
    expect(getByText("HX MALL")).toBeTruthy();
  });

  it("压深色底时字标反白", () => {
    const { getByText } = render(<Logo invert />);
    expect(getByText("虹选 · 好物").className).toContain("text-white");
  });
});
