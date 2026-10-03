import { describe, expect, it } from "vitest";
import { mediaThumbSrc } from "./media-thumb-src";

describe("待回收缩略图地址", () => {
  const key = "M1/S1/goods/202609/ab.jpg";

  it("后端给的绝对地址原样用 —— COS 缩略图与私有图签名都在这一支", () => {
    expect(mediaThumbSrc({ assetKey: key, thumbUrl: `https://img.hxmall.top/${key}!w200` }, "https://www.hxmall.top"))
      .toBe(`https://img.hxmall.top/${key}!w200`);
    const signed = `https://www.hxmall.top/cos-private/M1/S1/license/202609/cd.png?q-signature=x`;
    expect(mediaThumbSrc({ assetKey: key, thumbUrl: signed }, "https://www.hxmall.top")).toBe(signed);
  });

  it("相对路径补 API 前缀（本地盘 provider）", () => {
    expect(mediaThumbSrc({ assetKey: key, thumbUrl: `/uploads/${key}` }, "http://localhost:8082/"))
      .toBe(`http://localhost:8082/uploads/${key}`);
  });

  it("没有 thumbUrl 才退回老写法（mock、没升级的后端）", () => {
    expect(mediaThumbSrc({ assetKey: key }, "")).toBe(`/uploads/${key}`);
    expect(mediaThumbSrc({ assetKey: key, thumbUrl: null }, "http://x")).toBe(`http://x/uploads/${key}`);
  });
});
