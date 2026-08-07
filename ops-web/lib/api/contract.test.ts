// 契约一致性：mock 与 http 必须实现**同一组方法**，且不违反命名约定。
// 这条测试的价值在切后端那天：漏实现一个方法，翻转 USE_MOCK 后是运行时 undefined is not a function，
// 而不是编译错误（两边都是对象字面量，TS 只查各自是否满足接口，查不出「两边不一致」）。
import { describe, expect, it } from "vitest";
import { HTTP_SLICES, httpApi } from "./http";
import { MOCK_SLICES, mockApi } from "./mock";

const mockKeys = Object.keys(mockApi).sort();
const httpKeys = Object.keys(httpApi).sort();

describe("mock ↔ http 契约", () => {
  it("方法集合完全一致", () => expect(mockKeys).toEqual(httpKeys));

  it("方法非空（防止组合根漏拼某个切片）", () => expect(mockKeys.length).toBeGreaterThan(0));

  it("切片之间方法名不重叠（后者会静默覆盖前者）", () => {
    for (const [name, slices] of [["mock", MOCK_SLICES], ["http", HTTP_SLICES]] as const) {
      const seen = new Map<string, string>();
      for (const [domain, slice] of Object.entries(slices)) {
        for (const k of Object.keys(slice)) {
          expect(seen.has(k), `${name}: ${k} 同时由 ${seen.get(k)} 与 ${domain} 实现`).toBe(false);
          seen.set(k, domain);
        }
      }
    }
  });
});

describe("命名约定", () => {
  it("方法名不得跨域重名 —— 契约是扁平合并的，同名会静默覆盖", () => {
    // 真实踩过两次：风控的「解禁申诉」与评价的「差评申诉」都想叫 decideAppeal；
    // 风控的黑名单申诉状态与评价的申诉状态都想叫 AppealStatus。
    // 前者会让一个域的方法被另一个域覆盖（tsc 能发现，但等到那时已经写了一堆调用），
    // 后者会在 lib/types 的总出口上冲突。这条断言把它前移到"加方法就红"。
    const seen = new Map<string, string>();
    const dup: string[] = [];
    for (const [domain, slice] of Object.entries(MOCK_SLICES)) {
      for (const k of Object.keys(slice)) {
        if (seen.has(k)) dup.push(`${k}：${seen.get(k)} 与 ${domain}`);
        seen.set(k, domain);
      }
    }
    expect(dup, `方法名跨域重复：\n${dup.join("\n")}`).toEqual([]);
  });

  it("禁止 delete* —— 软删除一律 archive*/unarchive*（架构 §10.6）", () => {
    expect(mockKeys.filter((k) => /^delete/i.test(k))).toEqual([]);
  });

  it("有 archive* 就必须有配对的 unarchive*（只能归档不能恢复 = 硬删除）", () => {
    const archives = mockKeys.filter((k) => k.startsWith("archive"));
    for (const a of archives) {
      expect(mockKeys, `${a} 缺少配对的恢复方法`).toContain(`un${a}`);
    }
  });
});
