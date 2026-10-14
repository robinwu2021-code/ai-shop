import { describe, expect, it } from "vitest";
import { thumb } from "../src/utils/media-thumb";

/**
 * 列表缩略图（ADR-026）。挂错后缀的代价不对称：
 * 挂到别家地址上 → 那张图 404；该挂没挂 → 只是慢。所以「不是我们的就原样返回」是这组的重点。
 */
describe("缩略图后缀", () => {
  const K = "M1/S1/goods/202609/ab.jpg";

  it("两个出口都挂 !wNNN", () => {
    expect(thumb(`https://img.hxmall.top/${K}`, 375)).toBe(`https://img.hxmall.top/${K}!w375`);
    expect(thumb(`https://cdn.hxmall.top/${K}`, 200)).toBe(`https://cdn.hxmall.top/${K}!w200`);
  });

  it("别家地址原样返回 —— 挂上去就是 404", () => {
    const cos = `https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/${K}`;
    expect(thumb(cos, 375)).toBe(cos);
    expect(thumb("https://thirdwx.qlogo.cn/mmopen/x/132", 200)).toBe("https://thirdwx.qlogo.cn/mmopen/x/132");
    expect(thumb("https://img.hxmall.topx/a.jpg", 375)).toBe("https://img.hxmall.topx/a.jpg");
    expect(thumb("http://img.hxmall.top/a.jpg", 375)).toBe("http://img.hxmall.top/a.jpg");
  });

  it("emoji、本地临时路径、空值原样返回（sh-cover 会收到这几种）", () => {
    expect(thumb("🍚", 375)).toBe("🍚");
    expect(thumb("wxfile://tmp_abc.jpg", 375)).toBe("wxfile://tmp_abc.jpg");
    expect(thumb("", 375)).toBe("");
    expect(thumb(undefined, 375)).toBe("");
  });

  it("签名地址不动 —— 签名算进了路径，改了就 403", () => {
    const signed = `https://img.hxmall.top/${K}?q-signature=abc`;
    expect(thumb(signed, 375)).toBe(signed);
  });

  it("已经带后缀的不叠第二次", () => {
    expect(thumb(`https://img.hxmall.top/${K}!w375`, 200)).toBe(`https://img.hxmall.top/${K}!w375`);
  });
});
