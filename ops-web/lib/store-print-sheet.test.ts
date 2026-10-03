import { describe, expect, it } from "vitest";
import { buildPrintSheetHtml, type PrintSheetRow } from "./store-print-sheet";

const C = { title: "店铺码印刷页", empty: "这批里没有一家有码图", noImage: "无码图" };

const row = (over: Partial<PrintSheetRow> = {}): PrintSheetRow => ({
  storeNo: "ST903",
  storeName: "邻家便利·南门店",
  merchantName: "邻家便利",
  code: "shop_ST903_ab12",
  imageBase64: "iVBORw0KGgo=",
  ...over,
});

describe("店铺码印刷页", () => {
  it("有码图的行把图嵌进去 —— 这才是「导出给印刷」要的东西", () => {
    const html = buildPrintSheetHtml([row()], C);
    expect(html).toContain('src="data:image/png;base64,iVBORw0KGgo="');
    expect(html).toContain("邻家便利·南门店");
    expect(html).toContain("shop_ST903_ab12");
  });

  /**
   * 没码图的行**要出现**，标成「无码图」。
   *
   * 留空会被当成印刷失误去排查，而真实原因是那家店还没发码 —— 两件事要分得开。
   * 更糟的是塞一张占位图：它会被直接送去印刷。
   */
  it("没码图的行仍在页面上，标「无码图」而不是留空或塞占位图", () => {
    const html = buildPrintSheetHtml([row({ imageBase64: null, code: null })], C);
    expect(html).toContain("无码图");
    expect(html).not.toContain("data:image/png");
    // 行本身没有被丢掉：运营要看得见「这家还没发码」
    expect(html).toContain("邻家便利·南门店");
  });

  it("一行都没有时给一句话，而不是一张空白页", () => {
    const html = buildPrintSheetHtml([], C);
    expect(html).toContain("这批里没有一家有码图");
    expect(html).not.toContain("<figure>");
  });

  /**
   * <b>店名是商家自己填的</b>，直接拼进 innerHTML 就是在打印页上开一个注入口。
   * 「只给运营自己看」不是不转义的理由 —— 内容来自商家。
   */
  it("★ 店名里的尖括号被转义 —— 商家填的名字不能变成标签", () => {
    const html = buildPrintSheetHtml(
      [row({ storeName: '<img src=x onerror=alert(1)>', merchantName: "a&b" })], C,
    );
    expect(html).not.toContain("<img src=x");
    expect(html).toContain("&lt;img src=x");
    expect(html).toContain("a&amp;b");
  });

  it("没有门店名时退回门店号，不显示成空白", () => {
    const html = buildPrintSheetHtml([row({ storeName: null })], C);
    expect(html).toContain("ST903");
  });
});
