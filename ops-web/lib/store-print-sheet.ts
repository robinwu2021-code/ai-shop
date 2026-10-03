// 店铺码印刷页：把导出行拼成一张可直接打印的 HTML。
//
// 放在 lib/ 而不是页面旁边，是为了能**测**它：vitest 只收 `lib/**/*.test.ts`
// （见 vitest.config.ts），搁在 app/ 下的测试一条都不会跑 —— 那种「写了测试」
// 比没写更糟，因为它看起来已经被验证过了。
//
// 而它非测不可：真正的入口是 window.open，弹窗在自动化里打不开，
// 拼接逻辑留在页面里就等于永远没被验证过。
//
// 它回答的是「导出」这个动作的实际用途 —— 把物料交给印刷。
// CSV 装不下图，拿到五列文本还得再找人配图，等于没导。

/** 导出接口给的一行：列表那几列 + 码图（没发码/通道没开时为 null）。 */
export type PrintSheetRow = {
  storeNo: string;
  storeName: string | null;
  merchantName: string;
  code: string | null;
  imageBase64: string | null;
};

export type PrintSheetLabels = {
  title: string;
  empty: string;
  noImage: string;
};

/**
 * HTML 转义。
 *
 * <b>店名与商家名是用户填的</b>，直接拼进 innerHTML 就是把一个注入口开在打印页上。
 * 这页虽然只给运营自己看，但它的内容来自商家 —— 「只给自己人看」不是不转义的理由。
 */
function esc(s: string): string {
  return s.replace(/[&<>"']/g, (ch) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[ch]!));
}

/**
 * 拼出整张打印页。
 *
 * <b>没有码图的行照样出现在页面上，标成「无码图」而不是留空</b>：
 * 留空会被当成印刷失误去排查，而真实原因是那家店还没发码 —— 两件事要分得开。
 */
export function buildPrintSheetHtml(rows: PrintSheetRow[], c: PrintSheetLabels): string {
  const cells = rows.map((r) => {
    const name = esc(r.storeName ?? r.storeNo);
    const img = r.imageBase64
      ? `<img alt="${name}" src="data:image/png;base64,${esc(r.imageBase64)}" />`
      : `<div class="noimg">${esc(c.noImage)}</div>`;
    return `<figure>${img}<figcaption><b>${name}</b><br/>${esc(r.merchantName)}`
      + `<br/><code>${esc(r.code ?? "—")}</code></figcaption></figure>`;
  }).join("");

  return `<!doctype html><meta charset="utf-8"><title>${esc(c.title)}</title>
<style>
  body{font:14px/1.5 system-ui,sans-serif;margin:24px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:16px}
  figure{margin:0;text-align:center;border:1px solid #ddd;border-radius:8px;padding:12px}
  img{width:120px;height:120px;image-rendering:pixelated}
  .noimg{width:120px;height:120px;margin:0 auto;display:flex;align-items:center;
         justify-content:center;border:1px dashed #bbb;color:#999;border-radius:4px}
  figcaption{margin-top:8px;font-size:12px}
  @media print{figure{break-inside:avoid}}
</style>
<h1>${esc(c.title)}</h1>
${rows.length ? `<div class="grid">${cells}</div>` : `<p>${esc(c.empty)}</p>`}`;
}
