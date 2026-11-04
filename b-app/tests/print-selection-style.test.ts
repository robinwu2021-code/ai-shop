import { readFileSync } from "node:fs";
import { expect, it } from "vitest";
import { parse } from "@vue/compiler-sfc";
import postcss from "postcss";

function treatment(css: string, selector: string) {
  const style: Record<string, string> = {};
  postcss.parse(css).walkRules(rule => {
    if (rule.selector === selector) rule.walkDecls(d => { style[d.prop] = d.value; });
  });
  return style;
}
it.each(["print-center", "print-devices", "print-templates"])("%s gives selected paper/connection/tab options a distinct visual treatment", page => {
  const soft = treatment(readFileSync(new URL("../../packages/ui/src/styles/base.css", import.meta.url), "utf8"), ".sh-btn--soft");
  const { descriptor } = parse(readFileSync(new URL(`../src/pages/${page}/index.vue`, import.meta.url), "utf8"));
  const selected = treatment(descriptor.styles.map(s => s.content).join("\n"), ".print-selected");
  expect(["background", "color", "border", "outline", "box-shadow", "font-weight"].some(p => selected[p] && selected[p] !== soft[p])).toBe(true);
});
