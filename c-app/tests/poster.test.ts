/**
 * 分享海报（TDD-C 端裂变与商家招募 §3.2 B3 / §7.3）。
 *
 * <p>canvas 在 jsdom 里画不出真东西，所以这里守的不是「画得好不好看」，
 * 而是**三条会静默毁掉整张海报的规则**：
 *
 * <ol>
 *   <li>码是**店铺码**，不带邀请人。`wxacode.getUnlimited` 是永久码且总量有限，
 *       一人一张会烧穿额度 —— 而烧穿之后新入驻的商家再也拿不到码。</li>
 *   <li>拿不到码要**照样出图**。通道没开时后端返回 null，那是常态不是异常。</li>
 *   <li>H5 用**自己建的离屏画布**。往 uni 的 `<canvas>` 组件上画，导出来是全白的 ——
 *       组件内部按自己的指令队列重绘，把外面画的盖掉；而它自己的 draw 回调
 *       在自定义组件里又不回来。这一条是实测出来的，注释里记着。</li>
 * </ol>
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const src = readFileSync(resolve(__dirname, "../src/components/biz/biz-poster.vue"), "utf8");

describe("分享海报", () => {
  it("★★★ 码走 merchantAcode（店铺码），不拼 inviterNo —— 一人一张会烧穿额度", () => {
    expect(src).toContain("api.merchantAcode(");
    /*
     * **只看代码，不看注释**：文件里解释「为什么不把 inviterNo 编进码」的那句话
     * 本身就含这个词 —— 直接 not.toContain 会被自己的注释绊倒（守卫也扫注释）。
     * 判据换成「没有把它拼进请求参数」。
     */
    const code = src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
    expect(code, "把邀请人编进码的请求里了").not.toMatch(/merchantAcode\([^)]*inviter/i);
    expect(code).not.toMatch(/scene[^\n]*inviter/i);
  });

  it("★★★ 拿不到码照样出图 —— 通道没开是常态，不是异常", () => {
    // acodeImage 的每条失败分支都 return ""，而画码那一步用 if 包着
    expect(src).toContain('if (!base64) return "";');
    expect(src).toMatch(/if \(acodeImg\) \{/);
  });

  it("★★★ H5 自己建离屏画布，不往 uni 的 canvas 组件上画", () => {
    expect(src).toContain('document.createElement("canvas")');
    // uni 的那套只出现在小程序分支里
    const mpOnly = src.slice(src.indexOf("async function drawMp"));
    expect(mpOnly).toContain("uni.createCanvasContext");
  });

  it("★★ 商品图画不出来也不拦整张 —— 少一张图的海报仍然能发", () => {
    expect(src).toContain("img.onerror = () => resolve(null)");
    expect(src).toMatch(/if \(img\) \{/);
  });

  it("★★ 跨域图片要设 crossOrigin —— 不设的话 toDataURL 直接抛，连白底都导不出来", () => {
    expect(src).toContain('img.crossOrigin = "anonymous"');
  });

  it("★★ 点了才画：画布与下载都不该在进商品页时发生", () => {
    const page = readFileSync(resolve(__dirname, "../src/pages/goods/index.vue"), "utf8");
    expect(page).toContain('poster?.open()');
    expect(page).toContain("biz-poster");
  });
});
