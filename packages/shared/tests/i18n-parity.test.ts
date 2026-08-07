// 三语词条一致性。
//
// 这组断言的来历：`apply` 整个词条块在 `en/ar` 里**丢失了** —— 中文一切正常，
// 英文和阿语用户打开入驻页看到的是一片裸 key（`apply.title`、`apply.subject`…）。
//
// 这类问题没有任何东西挡得住：类型检查过、测试过、中文自测也过，
// 只会在真机上切到英文时以「界面出现英文 key」的形式被发现 —— 那时通常已经上线。
// 而它的成因很平常：某次编辑覆盖了一段，只改了一个语言文件。
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const APPS = ["c-app", "b-app"];
const LANGS = ["zh-CN", "en", "ar"] as const;
/** 中文是**基准语言**：文案先写中文，其余按它对齐 */
const BASE = "zh-CN";

/**
 * 把词条文件当模块导入太重（uni 环境、类型依赖），这里做轻量解析：
 * 只关心「有哪些 key」与「值里的占位符」，不关心值本身。
 */
async function loadLocale(app: string, lang: string): Promise<Record<string, string>> {
  const file = join(ROOT, app, `src/i18n/locale/${lang}.ts`);
  if (!existsSync(file)) throw new Error(`${app} 缺少 ${lang} 词条文件`);
  const src = readFileSync(file, "utf8");
  // 去掉 `export default` 与尾分号后，剩下的是一个对象字面量
  const body = src.slice(src.indexOf("{"), src.lastIndexOf("}") + 1);
  // eslint-disable-next-line @typescript-eslint/no-implied-eval, no-new-func
  const obj = new Function(`return (${body})`)() as Record<string, unknown>;

  // 拍平成 `a.b.c` → 值，便于逐条比对（嵌套差异也能定位到具体路径）
  const flat: Record<string, string> = {};
  const walk = (node: unknown, path: string) => {
    if (typeof node === "string") {
      flat[path] = node;
      return;
    }
    if (node && typeof node === "object") {
      for (const [k, v] of Object.entries(node)) walk(v, path ? `${path}.${k}` : k);
    }
  };
  walk(obj, "");
  return flat;
}

/** 取文案里的占位符，如 `买过 {n} 次` → ["{n}"] */
function placeholders(text: string): string[] {
  return [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[0]).sort();
}

describe("三语词条一致性", () => {
  for (const app of APPS) {
    it(`${app}: 三语的 key 集合完全一致`, async () => {
      const base = await loadLocale(app, BASE);
      const baseKeys = new Set(Object.keys(base));

      for (const lang of LANGS) {
        if (lang === BASE) continue;
        const other = await loadLocale(app, lang);
        const otherKeys = new Set(Object.keys(other));

        // 缺失是**用户可见的故障**：界面上直接显示 key
        const missing = [...baseKeys].filter((k) => !otherKeys.has(k));
        expect(
          missing,
          `${app}/${lang} 缺少 ${missing.length} 条词条（界面会显示裸 key）：\n${missing.slice(0, 20).join("\n")}`,
        ).toEqual([]);

        // 多余的多半是删中文时漏删，留着会让人以为还有这个功能
        const extra = [...otherKeys].filter((k) => !baseKeys.has(k));
        expect(
          extra,
          `${app}/${lang} 多出 ${extra.length} 条中文没有的词条：\n${extra.slice(0, 20).join("\n")}`,
        ).toEqual([]);
      }
    });

    it(`${app}: 三语的占位符一致`, async () => {
      // `{n}` 在中文有、英文漏写 → 英文用户看到半截句子（「bought times」）。
      // 这比缺整条更隐蔽：页面看着正常，只是少了个数字
      const base = await loadLocale(app, BASE);
      const bad: string[] = [];

      for (const lang of LANGS) {
        if (lang === BASE) continue;
        const other = await loadLocale(app, lang);
        for (const [key, text] of Object.entries(base)) {
          const want = placeholders(text);
          const got = placeholders(other[key] ?? "");
          if (want.join() !== got.join()) {
            bad.push(`${lang} ${key}: 中文 ${want.join("") || "无"} / 该语言 ${got.join("") || "无"}`);
          }
        }
      }
      expect(bad, `占位符不一致：\n${bad.join("\n")}`).toEqual([]);
    });
  }
});
