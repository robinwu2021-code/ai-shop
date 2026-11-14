// 商城原因码翻译表的守卫：mock 状态机抛出的每一个原因码都要有人话。
// 漏登记的表现是 toast 里冒出一串大写下划线 —— 运营看不懂，只能截图来问。
import { describe, expect, it, vi } from "vitest";
vi.mock("@/lib/stores/locale", () => ({ useLocaleStore: { getState: () => ({ locale: "zh" }) } }));
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { ApiError } from "@/lib/api/error";
import { MALL_REASON_CODES, describeError, humanError, mallReason } from "./commerce-reason";

const ROOT = join(import.meta.dirname, "..");

describe("商城原因码 → 人话", () => {
  it("mock 状态机里出现的每个原因码都已登记", () => {
    const src = readFileSync(join(ROOT, "lib/mock/db/commerce.ts"), "utf8");
    const thrown = new Set<string>();
    for (const m of src.matchAll(/(?:mallFail\(|need\([^;]*?,\s*)"([A-Z][A-Z0-9_]+)"\)/g)) thrown.add(m[1]!);
    expect(thrown.size, "一个原因码都没扫到，正则或写法变了？").toBeGreaterThan(20);
    const missing = [...thrown].filter((c) => !MALL_REASON_CODES.includes(c)).sort();
    expect(missing, "这些原因码没有登记进 lib/commerce-reason.ts").toEqual([]);
  });

  it("接口文档列出的线下原因码也都已登记", () => {
    const doc = readFileSync(join(ROOT, "../docs/api/commerce-mall-http.md"), "utf8");
    const section = doc.slice(doc.indexOf("常见原因码"));
    const listed = [...section.matchAll(/`([A-Z][A-Z0-9_]+)`/g)].map((m) => m[1]!);
    expect(listed.length).toBeGreaterThan(10);
    expect(listed.filter((c) => !MALL_REASON_CODES.includes(c))).toEqual([]);
  });

  it("命中才翻译并保留原码；未登记的错误原样", () => {
    expect(describeError(new ApiError(10409, "OFFLINE_BANK_REFERENCE_CONFLICT"))).toContain("（OFFLINE_BANK_REFERENCE_CONFLICT）");
    expect(describeError(new Error("网络异常"))).toBe("网络异常");
    expect(mallReason("NOPE")).toBeNull();
    const h = humanError(new ApiError(10409, "VERSION_CONFLICT")) as ApiError;
    expect(h.code).toBe(10409);
    expect(h.message).toMatch(/版本/);
  });
});
