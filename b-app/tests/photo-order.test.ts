/**
 * 商品图拖排（TDD-商品编辑页-录入落点与发布历史 §15 · AC19）。
 *
 * <p>断言一律看**两列各自的值**，不看「数组长度没变」—— 这里真正会错的是封面指针：
 * 挪到第一位却没换封面的话，界面上那枚「主图」角标还贴在原来那张上，而列表页也照旧用它。
 */
import { describe, expect, it } from "vitest";
import { reorderPhotos } from "@/pages/goods-edit/photo-order";

const P = ["a.jpg", "b.jpg", "c.jpg", "d.jpg"];

describe("商品图拖动排序", () => {
  it("★★★ 挪到第一位 = 换封面", () => {
    expect(reorderPhotos(P, 2, 0)).toEqual({
      cover: "c.jpg",
      images: ["c.jpg", "a.jpg", "b.jpg", "d.jpg"],
    });
  });

  it("★★★ 把封面挪走 = 顶上来的那张成为封面", () => {
    expect(reorderPhotos(P, 0, 3)).toEqual({
      cover: "b.jpg",
      images: ["b.jpg", "c.jpg", "d.jpg", "a.jpg"],
    });
  });

  it("中间互换不动封面", () => {
    const r = reorderPhotos(P, 1, 2);
    expect(r.cover).toBe("a.jpg");
    expect(r.images).toEqual(["a.jpg", "c.jpg", "b.jpg", "d.jpg"]);
  });

  it("没挪动、越界：原样返回（调用方不用自己判）", () => {
    expect(reorderPhotos(P, 1, 1).images).toEqual(P);
    expect(reorderPhotos(P, 9, 0).images).toEqual(P);
    expect(reorderPhotos([], 0, 0)).toEqual({ cover: "", images: [] });
  });
});
