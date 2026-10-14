/**
 * 保存商品的**请求体**：页面交给 `mSaveGoods` 的字段，`http.ts` 逐字段重建时一个都不能丢。
 *
 * <p>为什么值得单独一份（2026-10-07）：页面一直在交 `restrictedRegions` 与 `entrySource`，
 * 而 `http.ts` 那份逐字段的对象里没有这两行 —— 生产上**没有一件商品存下过限购地区**，
 * 提交历史的「怎么录的」恒为 MANUAL。mock 收整个对象，所以 H5 上一切正常；
 * wire-alignment 守卫只比**类型**，可选字段在 `satisfies` 下漏写也不报。
 * 这里替掉底层传输，直接看发出去的 body。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";

const post = vi.fn(async (..._args: unknown[]) => ({}));
vi.mock("@shared/net/http-client", () => ({
  http: { post, get: vi.fn(async () => ({})), put: vi.fn(async () => ({})), del: vi.fn(async () => ({})) },
}));

const { httpApi } = await import("@/api/http");

function draft(over: Record<string, unknown> = {}) {
  return {
    title: { "zh-CN": "脆柿子" }, subtitle: { "zh-CN": "" }, categoryNo: "CAT120",
    images: [], detailImages: [], detail: "", params: [], specGroups: [], skus: [],
    fulfillments: ["EXPRESS"], limitPerUser: 0,
    ...over,
  } as never;
}

describe("保存商品：页面交的字段都要发出去", () => {
  beforeEach(() => post.mockClear());

  it("★★★ 限购地区与录入方式在请求体里", async () => {
    await httpApi.mSaveGoods(draft({ restrictedRegions: ["650000", "540000"], entrySource: "QUICK_TEXT" }));
    const body = post.mock.calls[0]![1] as Record<string, unknown>;
    expect(body.restrictedRegions).toEqual(["650000", "540000"]);
    expect(body.entrySource).toBe("QUICK_TEXT");
  });

  it("空数组 = 清空恢复全国，也要原样发（不能变成不传）", async () => {
    await httpApi.mSaveGoods(draft({ restrictedRegions: [] }));
    const body = post.mock.calls[0]![1] as Record<string, unknown>;
    expect("restrictedRegions" in body).toBe(true);
    expect(body.restrictedRegions).toEqual([]);
  });
});
