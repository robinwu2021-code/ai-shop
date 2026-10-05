import { describe, expect, it } from "vitest";
import { classifyZip } from "@shared/ports/zip-media";
import names from "./fixtures/zip-yangfeng.json";

/**
 * 用**真实压缩包**的文件名跑分类排序（商家发来的「阳丰脆柿.zip」，
 * 名单见 fixtures/zip-yangfeng.json，zip 本体在仓库外 ~/work/appdata/ai-shop/goods）。
 *
 * 这个真实包暴露过两个假设错误，所以它必须作为回归钉住：
 *  1. 顶层多一层**商品名目录**（阳丰脆柿/主图/…），不是根目录直接放主图/详情；
 *  2. 详情目录叫**「详情页」**不是「详情」—— 早先精确匹配 `/详情/` 会漏掉它，
 *     16 张详情图会被当成主图。放宽成「目录段包含关键词」才对。
 */
describe("真实压缩包分类（阳丰脆柿）", () => {
  const r = classifyZip(names as string[]);

  it("★★★ 主图 5 张，按 1..5 顺序", () => {
    expect(r.main).toEqual([
      "阳丰脆柿/主图/1.jpg", "阳丰脆柿/主图/2.jpg", "阳丰脆柿/主图/3.jpg",
      "阳丰脆柿/主图/4.jpg", "阳丰脆柿/主图/5.jpg",
    ]);
  });

  it("★★★ 「详情页」识别为详情，16 张按数字序（1,2,…,10,…,16 不是字符串序）", () => {
    expect(r.detail).toHaveLength(16);
    expect(r.detail[0]).toBe("阳丰脆柿/详情页/1.jpg");
    expect(r.detail[1]).toBe("阳丰脆柿/详情页/2.jpg");
    expect(r.detail[9]).toBe("阳丰脆柿/详情页/10.jpg");   // 字符串序这里会是 10 排到 2 前
    expect(r.detail[15]).toBe("阳丰脆柿/详情页/16.jpg");
  });

  it("没有 txt → undefined；详情图一张都没漏进主图", () => {
    expect(r.txt).toBeUndefined();
    expect(r.main).toHaveLength(5);   // 漏判时这里会变成 5+16
  });
});
