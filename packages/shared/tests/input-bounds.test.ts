// 每个输入框都要有长度上限。
//
// **为什么这是一条闸门而不是一次清理**：2026-08-27 扫的时候，134 个 `<input>` 里
// 只有 23 个带 `maxlength`。少掉的那 111 个不会报错、不会变红 —— 人可以往
// 「收货人姓名」里粘一千个字，前端一路放行，后端 `usr_address.name` 是
// `VARCHAR(64)`：MariaDB 严格模式下是 1406 报错（页面上只看到一句「操作失败」），
// 非严格模式下**静默截断**（更糟：他以为存好了）。
//
// **上限从哪来**：能对上库里列的走列长（如 `usr_address.tag` 是 16、
// `ord_sub_order.verify_code` 是 16），对不上的按类别取一档 ——
// 金额 10（9999999.99）、计数 6、天数 4、时刻 5「08:00」、日期 10、搜索词 32、
// 短名 64、长文 255。**一条硬规矩：任何一处都不许超过它对应的列长**，
// 那是唯一会真出事的方向；比列短只是手感问题。
//
// 补的时候抓到两处按名字归错档的，都写在这儿当反例：
//   · `form.settleAccount` 的尾巴是「…Account」，被 `count$` 当成计数 →
//     银行账号会被截到 6 位
//   · `draft.tag` 的类别默认 64，而 `usr_address.tag` 只有 16
// 两处都是「规则看着挺对，落到具体字段上是错的」。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { globSync } from "node:fs";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * 只看 `<template>` 段，且去掉注释。
 *
 * 不这么做的话 `phone-gate.vue` 的 **JS 注释里**那句 `<input type="number">`
 *（它在解释 v-model 会把手机号转成数字这个坑）会被当成真的输入框，
 * 然后闸门要求给一段注释加 `maxlength`。
 */
function template(src: string): string {
  const m = /<template>([\s\S]*)<\/template>/.exec(src);
  return (m?.[1] ?? "").replace(/<!--[\s\S]*?-->/g, "");
}

function inputsWithoutBound(app: string): string[] {
  const out: string[] = [];
  for (const f of globSync(`${app}/src/**/*.vue`, { cwd: ROOT })) {
    const tpl = template(readFileSync(join(ROOT, f), "utf8"));
    for (const m of tpl.matchAll(/<input\b[^>]*?\/?>/gs)) {
      if (/\bmaxlength=/.test(m[0])) continue;
      const model = /v-model(?:\.\w+)?="([^"]+)"/.exec(m[0])?.[1]
        ?? /:value="([^"]+)"/.exec(m[0])?.[1]
        ?? "(无绑定)";
      out.push(`${f} → ${model}`);
    }
  }
  return out;
}

describe("输入框长度上限", () => {
  for (const app of ["b-app", "c-app"]) {
    it(`★★ ${app}：每个 <input> 都要有 maxlength`, () => {
      const bad = inputsWithoutBound(app);
      expect(
        bad,
        `${bad.length} 个输入框没有长度上限。粘进去多长都收，而库里的列是有长度的：\n` +
          bad.join("\n") +
          "\n→ 上限的取法见本文件顶部；**不许超过对应列的长度**",
      ).toEqual([]);
    });
  }
});
