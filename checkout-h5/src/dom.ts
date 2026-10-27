/** 极小的建节点工具。<b>只用 textContent</b>：商户名、门店名都来自服务端，不经 innerHTML，杜绝注入 */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Record<string, string> = {},
                                                          ...children: (Node | string | null | false)[]) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) el.setAttribute(k, v);
  for (const c of children) {
    if (c === null || c === false) continue;
    el.append(typeof c === "string" ? document.createTextNode(c) : c);
  }
  return el;
}

/** 金额与数字在阿拉伯语页面里也保持从左到右 */
export function ltr(text: string): HTMLElement {
  return h("bdi", { dir: "ltr" }, text);
}
