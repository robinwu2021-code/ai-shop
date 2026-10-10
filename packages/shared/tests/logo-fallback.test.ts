import { describe, expect, it } from "vitest";
import { readFileSync, globSync } from "node:fs";
import { resolve } from "node:path";

/**
 * **商家头像不许裸渲染。**
 *
 * `logo` 在后端可空，商家不上传是常态。空字符串画出来是一个灰色空方块 ——
 * 不报错、不塌版，看着像头像正在加载。mock 里每家都带 emoji，
 * 所以这个情况在接真后端之前一次都没出现过（商品封面同一个坑，
 * 已经有 GOODS_COVER_FALLBACK，这条守的是另一半）。
 *
 * <p>规则：模板里出现 `xxx.logo` 的插值，必须同时带 `MERCHANT_LOGO_FALLBACK`。
 */
const ROOT = resolve(__dirname, "../../..");

describe("商家头像：必须有兜底", () => {
  it("★ 模板里每处 .logo 插值都带 MERCHANT_LOGO_FALLBACK", () => {
    const offenders: string[] = [];
    for (const rel of globSync("{c-app,b-app}/src/**/*.vue", { cwd: ROOT }).sort()) {
      const src = readFileSync(resolve(ROOT, rel), "utf-8");
      const tpl = /<template>([\s\S]*)<\/template>/.exec(src)?.[1] ?? "";
      for (const m of tpl.matchAll(/\{\{[^}]*?\.logo\b[^}]*?\}\}/g)) {
        if (!m[0].includes("MERCHANT_LOGO_FALLBACK")) {
          offenders.push(`${rel} → ${m[0].trim()}`);
        }
      }
    }
    expect(
      offenders,
      "这些地方直接渲染 merchant.logo，商家没上传头像时会是一个灰色空方块。\n" +
        "改成 `xxx.logo || MERCHANT_LOGO_FALLBACK`（@shared/utils/constants）。\n" +
        offenders.join("\n"),
    ).toEqual([]);
  });
});
