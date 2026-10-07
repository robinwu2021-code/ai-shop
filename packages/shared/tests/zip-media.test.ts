import { describe, expect, it } from "vitest";
import { classifyZip, dropJunk, listsOf, ruleHint, sortByNumber } from "@shared/ports/zip-media";

/** 压缩包归类/排序（TDD-商品快速录入 §5 · AC1/AC2）。纯字符串逻辑，不碰解压。 */
describe("压缩包分类与排序", () => {
  it("★★★ AC1 按文件名数字排，不是字符串序", () => {
    // 字符串序会把 10 排到 2 前面 —— 这正是消融要红的点
    expect(sortByNumber(["主图/10.jpg", "主图/2.jpg", "主图/1.jpg"]))
      .toEqual(["主图/1.jpg", "主图/2.jpg", "主图/10.jpg"]);
    // 没数字的排最后，不是报错也不是排前面
    expect(sortByNumber(["b.jpg", "1.jpg"])).toEqual(["1.jpg", "b.jpg"]);
  });

  it("★★★ AC1 主图/详情按目录分，各自按序号", () => {
    const r = classifyZip([
      "主图/2.jpg", "主图/1.jpg",
      "详情/10.jpg", "详情/1.jpg",
      "说明.txt",
    ]);
    expect(r.main).toEqual(["主图/1.jpg", "主图/2.jpg"]);
    expect(r.detail).toEqual(["详情/1.jpg", "详情/10.jpg"]);
    expect(r.txt).toBe("说明.txt");
  });

  it("AC1 英文目录名、大小写不敏感", () => {
    const r = classifyZip(["Main/1.jpg", "DETAIL/1.png"]);
    expect(r.main).toEqual(["Main/1.jpg"]);
    expect(r.detail).toEqual(["DETAIL/1.png"]);
  });

  it("★★★ AC2 没有目录结构时：图全当主图，详情空", () => {
    const r = classifyZip(["a.jpg", "b.jpg"]);
    expect(r.main).toEqual(["a.jpg", "b.jpg"]);
    expect(r.detail).toEqual([]);
  });

  it("AC2 没带 txt → txt 为 undefined", () => {
    expect(classifyZip(["主图/1.jpg"]).txt).toBeUndefined();
  });

  it("非图、子目录里的 txt、缓存文件都忽略", () => {
    const r = classifyZip([
      "主图/1.jpg", "主图/.DS_Store", "主图/readme.md",
      "详情/note.txt",   // 子目录 txt 不算商品文字
    ]);
    expect(r.main).toEqual(["主图/1.jpg"]);
    expect(r.txt).toBeUndefined();
  });
});

/** 交给模型分类之前与之后的两步（TDD-商品压缩包导入 AC11/AC12）。 */
describe("压缩包：规则先验与按分法取图", () => {
  it("★★ 系统垃圾先滤掉：__MACOSX、._ 资源叉、.DS_Store、Thumbs.db", () => {
    expect(dropJunk([
      "主图/1.jpg", "__MACOSX/主图/._1.jpg", "主图/._2.jpg", "主图/.DS_Store", "详情/Thumbs.db", "详情/1.jpg",
    ])).toEqual(["主图/1.jpg", "详情/1.jpg"]);
  });

  it("★★★ 规则分法每个文件一条：主图/详情带序号、txt 是文案、其余不导", () => {
    const all = ["主图/2.jpg", "主图/1.jpg", "详情/1.jpg", "说明.txt", "资料/规格.pdf"];
    const hint = ruleHint(classifyZip(all), all);
    expect(hint).toEqual([
      { path: "主图/2.jpg", target: "MAIN", order: 2 },
      { path: "主图/1.jpg", target: "MAIN", order: 1 },
      { path: "详情/1.jpg", target: "DETAIL", order: 1 },
      { path: "说明.txt", target: "TEXT", order: 0 },
      { path: "资料/规格.pdf", target: "IGNORE", order: 0 },
    ]);
  });

  it("★★★ 按分法取图：各自按 order 排，IGNORE 不出现，文案取第一份", () => {
    const r = listsOf([
      { path: "长图/b.png", target: "DETAIL", order: 2 },
      { path: "首图/白底.jpg", target: "MAIN", order: 1 },
      { path: "长图/a.png", target: "DETAIL", order: 1 },
      { path: "资质/证.jpg", target: "IGNORE", order: 0 },
      { path: "文案.txt", target: "TEXT", order: 0 },
    ]);
    expect(r.main).toEqual(["首图/白底.jpg"]);
    expect(r.detail).toEqual(["长图/a.png", "长图/b.png"]);
    expect(r.txt).toBe("文案.txt");
  });

  it("★★ 根目录 txt 要用相对路径判：绝对路径里永远有 /，规则会认不出", () => {
    expect(classifyZip(["说明.txt"]).txt).toBe("说明.txt");
    expect(classifyZip(["/storage/emulated/0/doc/goods-zip-1/说明.txt"]).txt).toBeUndefined();
  });
});
