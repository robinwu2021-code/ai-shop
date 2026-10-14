import { describe, expect, it } from "vitest";
import { fitLongEdge, MAX_IMAGE_EDGE } from "../src/ports/media";

/** 上传前等比缩小（ADR-026 T7）：长边压到 1600，小图不动 —— 小图再压一遍只会更糊。 */
describe("上传前缩图尺寸", () => {
  it("横图、竖图都按长边等比缩", () => {
    expect(fitLongEdge(4000, 3000)).toEqual({ width: 1600, height: 1200 });
    expect(fitLongEdge(3000, 4000)).toEqual({ width: 1200, height: 1600 });
  });

  it("没超过上限的不缩（等于上限也不缩）", () => {
    expect(fitLongEdge(1200, 800)).toBeNull();
    expect(fitLongEdge(MAX_IMAGE_EDGE, 900)).toBeNull();
  });

  it("拿不到尺寸时不缩，交给原图 —— 压缩不能挡住上传", () => {
    expect(fitLongEdge(0, 0)).toBeNull();
    expect(fitLongEdge(Number.NaN, 100)).toBeNull();
  });
});
