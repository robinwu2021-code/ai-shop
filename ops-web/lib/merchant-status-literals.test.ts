// 页面里写死的商家状态词，必须真的存在于 `MerchantStatus`。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这道闸在防什么
// ─────────────────────────────────────────────────────────────────────────────
// `MerchantQ.status` 是 `string`（它逗号分隔多态，收窄会误伤商家页），
// 于是写错一个词照样编译过。2026-09-03 实测两处写着 `status: "APPROVED"` ——
// 那是**进件申请单**的词，商家档案上从来没有过。
//
// 失败方式极安静：真后端按 `status=APPROVED` 查，返回 0 条；
// 页面不报错、控制台干净，下拉框只是空的 —— 客服会以为「一个商家都没有」。
// mock 那边也是空的，所以演示、单测、E2E 全绿。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "..");

/** 从类型定义里读词表 —— 不在这儿抄一份，抄的那份迟早与类型分岔。 */
function vocabulary(): string[] {
  const src = readFileSync(join(ROOT, "lib/types/merchant.ts"), "utf8");
  const m = /export type MerchantStatus\s*=\s*([^;]+);/.exec(src);
  if (!m) throw new Error("MerchantStatus 没解出来 —— 类型定义的形状变了，先改这个解析器");
  return [...m[1].matchAll(/"([A-Z_]+)"/g)].map((x) => x[1]);
}

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return tsxFiles(full);
    return /\.tsx?$/.test(name) && !name.endsWith(".test.ts") ? [full] : [];
  });
}

describe("商家状态词", () => {
  it("★★ listMerchants 里写死的 status 必须是真的状态词 —— 写错只会让下拉框空着", () => {
    const known = vocabulary();
    const bad: string[] = [];
    for (const file of tsxFiles(join(ROOT, "app"))) {
      const src = readFileSync(file, "utf8");
      for (const m of src.matchAll(/listMerchants\(\s*\{[^}]*status:\s*"([^"]+)"/g)) {
        // 逗号分隔多态：每一段都要在词表里
        for (const one of m[1].split(",").map((x) => x.trim()).filter(Boolean)) {
          if (!known.includes(one)) bad.push(`${file.slice(ROOT.length + 1)}: ${one}`);
        }
      }
    }
    expect(
      bad,
      `这些商家状态词不存在（词表：${known.join(" / ")}）：\n  ${bad.join("\n  ")}\n`
      + "→ 真后端会返回 0 条，而页面看不出任何异常",
    ).toEqual([]);
  });
});
