// 模板占位替换与「哪些通道能填什么」的规则。
//
// 纯函数放 lib：本仓的测试是 environment=node、只测 lib（见 vitest.config），
// 而「预览与真正发出去的不一致」是这块最容易出的错 —— 它必须能被测。

/** 模板正文里的占位形如 `{code}` / `{thing2}`。 */
const PLACEHOLDER = /\{(\w+)\}/g;

/**
 * 把占位代入实际值，得到「发出去长什么样」。
 *
 * <p><b>没填的占位保持原样</b>（还显示成 `{code}`），不替换成空 ——
 * 空着会让预览看起来像一句通顺的话，而实际发出去中间少一块。
 */
export function renderTemplate(content: string, values: Record<string, string>): string {
  return content.replace(PLACEHOLDER, (whole, key: string) => {
    const v = values[key];
    return v == null || v === "" ? whole : v;
  });
}

/** 模板正文里用到的占位名，按出现顺序去重。 */
export function placeholdersOf(content: string): string[] {
  const out: string[] = [];
  for (const m of content.matchAll(PLACEHOLDER)) {
    const key = m[1];
    if (key && !out.includes(key)) out.push(key);
  }
  return out;
}

/**
 * 这条通道的正文能不能整段改。
 *
 * <p><b>短信与微信不能</b>：通道方只接受已报备的模板，自由文本会被直接拒
 * （阿里云收 `TemplateCode`+`TemplateParam`；微信收 `template_id`+`data`）。
 * 能填的只是模板里的参数格。其余通道是我们自己的内容，随便改。
 */
export function isFreeText(channel: string): boolean {
  return channel === "MAIL" || channel === "PUSH" || channel === "INAPP";
}

/**
 * 选中的微信模板 → 后端要发哪一条（场景码）。
 *
 * <p><b>为什么不加一个「场景」下拉</b>：抽屉里本来就要选模板，而微信正好一条模板
 * 对一个场景。再加一个控件就有两个能互相矛盾的输入 —— 选了「退款通知」模板、
 * 场景却停在「到货」，发出去的是到货那条，而页面上两处都写着退款。
 *
 * <p>额度是**逐模板**授权的（用户点「允许」的是哪条就只有哪条有额度），
 * 所以这个值也决定预检查哪条模板的额度。认不出时回落到货 —— 两条里它更常用，
 * 且不会误发退款话术。
 */
export function wxSceneOf(templateNo: string | undefined): string {
  return templateNo && /REFUND/i.test(templateNo) ? "REFUNDED" : "ORDER_ARRIVED";
}
