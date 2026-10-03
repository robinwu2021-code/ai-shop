// ER 图的 SVG 布局器。
//
// 为什么不用 mermaid：写错一个字符整张图不显示，**而且不报错**。
// 本仓库为此丢过图，直到有人问「这里怎么是空的」。SVG 到处都能打开，
// 且布局可控 —— 内容一多 mermaid 就糊，这正是 ER 图最常见的情况。

const PAL = {
  ink: "#161A21", muted: "#5A6472", line: "#8A94A2", rule: "#D8DEE6",
  box: "#EAF1F6", boxLine: "#8FB3C9", base: "#EDEFF2", baseLine: "#B3B9C2",
  hi: "#E6F2F0", hiLine: "#8FC2BA",
};

const esc = (t) =>
  String(t).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** 中文按 1 个字宽算，西文按 0.55 —— 用于判断文字放不放得下 */
const width = (t, size) =>
  [...String(t)].reduce((n, c) => n + (/[一-龥]/.test(c) ? 1 : 0.55), 0) * size;

const head = (w, h, title, desc) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" role="img" font-family="system-ui,-apple-system,'PingFang SC',sans-serif">
<title>${esc(title)}</title><desc>${esc(desc)}</desc>
<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
<path d="M2 1L8 5L2 9" fill="none" stroke="${PAL.line}" stroke-width="1.4" stroke-linecap="round"/></marker>
<style>.n{font-weight:500;fill:${PAL.ink}}.s{font-size:11px;fill:${PAL.muted}}
.l{font-size:10.5px;fill:${PAL.muted};paint-order:stroke;stroke:#F7F9FB;stroke-width:3.5;stroke-linejoin:round}.b{font-size:11px;fill:${PAL.muted};letter-spacing:.1em}
.ar{stroke:${PAL.line};stroke-width:1.3;fill:none}</style></defs>`;

/**
 * 按**实际宽度**截断并补省略号。
 *
 * 之前是 `.slice(0, 14)` —— 定长切割在中英混排下必然出错：
 * 「用户积分账户（银行 + 诞生」这种半截括号就是它切出来的，
 * 读者会以为注释本身写错了。
 */
const fit = (t, max, size) => {
  const s = String(t ?? "");
  if (!s || width(s, size) <= max) return s;
  let out = "";
  for (const c of s) {
    if (width(out + c, size) > max - width("…", size)) break;
    out += c;
  }
  return out.replace(/[（(【\s]+$/, "") + "…";
};

const box = (x, y, w, h, name, sub, kind = "box") => {
  const fill = kind === "base" ? PAL.base : kind === "hi" ? PAL.hi : PAL.box;
  const stroke = kind === "base" ? PAL.baseLine : kind === "hi" ? PAL.hiLine : PAL.boxLine;
  // 名字放不下就缩字号，宁可小一号也不要溢出边框
  const size = width(name, 13) > w - 14 ? (width(name, 11) > w - 12 ? 9.5 : 11) : 13;
  return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="6" fill="${fill}" stroke="${stroke}"/>
<text class="n" x="${x + w / 2}" y="${y + (sub ? 20 : h / 2 + 4)}" text-anchor="middle" font-size="${size}">${esc(name)}</text>
${sub ? `<text class="s" x="${x + w / 2}" y="${y + 35}" text-anchor="middle">${esc(fit(sub, w - 12, 11))}</text>` : ""}`;
};

/**
 * 域级总览：三条带。
 *
 * 分带依据是**被引用次数**而不是拓扑序 —— 域之间有环
 * （`cmt → mkt → usr → cmt`），拓扑排序会失败或给出误导性的顺序。
 */
export function domainOverview(domains, edges) {
  const refCount = new Map();
  for (const key of Object.keys(edges)) {
    const to = key.split(">")[1];
    refCount.set(to, (refCount.get(to) ?? 0) + 1);
  }
  const by = (d) => refCount.get(d.prefix) ?? 0;
  const sorted = [...domains].sort((a, b) => by(b) - by(a));
  const tiers = [
    { label: "基础 · 被引用最多", items: sorted.filter((d) => by(d) >= 5), kind: "base" },
    { label: "主数据与交易", items: sorted.filter((d) => by(d) >= 2 && by(d) < 5), kind: "box" },
    { label: "派生与记账", items: sorted.filter((d) => by(d) < 2), kind: "hi" },
  ].filter((t) => t.items.length);

  const W = 680, PAD = 36, BH = 46, GAP = 10;
  let y = 50;
  const parts = [];
  for (const [ti, tier] of tiers.entries()) {
    const n = tier.items.length;
    const bw = Math.min(150, Math.floor((W - PAD * 2 - GAP * (n - 1)) / n));
    const x0 = (W - (bw * n + GAP * (n - 1))) / 2;
    parts.push(`<line x1="${PAD}" y1="${y - 24}" x2="${W - PAD}" y2="${y - 24}" stroke="${PAL.rule}"/>`);
    parts.push(`<text class="b" x="${PAD}" y="${y - 10}">${esc(tier.label)}</text>`);
    tier.items.forEach((d, i) =>
      parts.push(box(x0 + i * (bw + GAP), y, bw, BH, d.label, `${d.prefix}_* · ${d.count} 表`, tier.kind)),
    );
    if (ti < tiers.length - 1) {
      const ay = y + BH + 6;
      parts.push(`<line class="ar" x1="${W / 2}" y1="${ay + 22}" x2="${W / 2}" y2="${ay + 2}" marker-end="url(#a)"/>`);
      parts.push(`<text class="l" x="${W / 2 + 8}" y="${ay + 16}">依赖</text>`);
    }
    y += BH + 52;
  }
  return head(W, y - 32, "数据库域总览",
    "按被引用次数分为三条带，箭头表示下层依赖上层。域之间存在环，故不画成有向无环图。") +
    parts.join("\n") + "</svg>";
}

/** 域内表关系：按依赖深度分层，箭头由引用方指向被引用方 */
export function tableGraph(domainLabel, tables, rels) {
  const names = tables.map((t) => t.name);
  const depth = new Map(names.map((n) => [n, 0]));
  // 迭代求深度：被引用的更浅。**有环时迭代次数封顶**，不会死循环
  for (let round = 0; round < names.length; round += 1) {
    let moved = false;
    for (const r of rels) {
      const want = (depth.get(r.to) ?? 0) + 1;
      if (want > (depth.get(r.from) ?? 0) && want < names.length) {
        depth.set(r.from, want);
        moved = true;
      }
    }
    if (!moved) break;
  }
  const byDepth = [];
  for (const n of names) (byDepth[depth.get(n)] ??= []).push(n);
  // 一排最多 4 个：再多框就窄到装不下 `mkt_request_interest` 这种长表名，
  // 缩到 9.5px 还是会溢出边框。**宁可多占一行也不要读不出名字。**
  const layers = [];
  for (const layer of byDepth) {
    if (!layer?.length) continue;
    const rows = Math.ceil(layer.length / 4);
    const per = Math.ceil(layer.length / rows);
    for (let i = 0; i < layer.length; i += per) layers.push(layer.slice(i, i + per));
  }

  const W = 680, PAD = 30, BH = 44, VGAP = 72, HGAP = 22;
  const pos = new Map();
  let y = 30;
  const boxes = [];
  for (const layer of layers) {
    if (!layer?.length) continue;
    const n = layer.length;
    const bw = Math.min(170, Math.floor((W - PAD * 2 - HGAP * (n - 1)) / n));
    const x0 = (W - (bw * n + HGAP * (n - 1))) / 2;
    layer.forEach((name, i) => {
      const x = x0 + i * (bw + HGAP);
      pos.set(name, { x, y, w: bw, h: BH });
      const t = tables.find((z) => z.name === name);
      boxes.push(box(x, y, bw, BH, name, t?.comment ?? ""));
    });
    y += BH + VGAP;
  }

  const arrows = [], labels = [];
  let maxDraw = 0;
  // 同一条水平走线上多条边会叠在一起、标签互相盖住 —— 按「这一层第几条」错开
  const laneOf = new Map();
  rels.forEach((r) => {
    const key = `${pos.get(r.from)?.y}>${pos.get(r.to)?.y}`;
    laneOf.set(r, (laneOf.get(key) ?? 0));
    laneOf.set(key, (laneOf.get(key) ?? 0) + 1);
  });
  for (const r of rels) {
    const a = pos.get(r.from), b = pos.get(r.to);
    if (!a || !b) continue;
    const lane = laneOf.get(r) ?? 0;
    if (a.y === b.y) {
      // 同层：**绕到框下面走**。直着穿过去会被中间的框盖住，
      // 看起来像断线，标签也整个消失。
      const [l, rr] = a.x < b.x ? [a, b] : [b, a];
      const dip = a.y + a.h + 12 + lane * 13;
      maxDraw = Math.max(maxDraw, dip);
      const back = a.x < b.x;
      arrows.push(
        `<path class="ar" d="M${l.x + l.w / 2} ${l.y + l.h} L${l.x + l.w / 2} ${dip} L${rr.x + rr.w / 2} ${dip} L${rr.x + rr.w / 2} ${rr.y + rr.h + 3}" marker-${back ? "end" : "start"}="url(#a)"/>`,
      );
      labels.push(`<text class="l" x="${(l.x + l.w + rr.x) / 2}" y="${dip - 3}" text-anchor="middle">${esc(r.col)}</text>`);
    } else {
      // 引用方不一定在被引用方下面（同深度换行后可能在上面），
      // 起终点要按实际相对位置取，否则线会从框里穿出来
      const up = a.y > b.y;
      const x1 = a.x + a.w / 2, x2 = b.x + b.w / 2;
      const y1 = up ? a.y : a.y + a.h;
      const y2 = up ? b.y + b.h : b.y;
      // 横段走在**被引用方所在行的紧邻走廊**里，而不是两点中间 ——
      // 换行后中点可能正落在中间那排框上，标签会被框整个盖掉
      const mid = up
        ? b.y + b.h + 18 + (lane % 3) * 12
        : b.y - 18 - (lane % 3) * 12;
      arrows.push(`<path class="ar" d="M${x1} ${y1} L${x1} ${mid} L${x2} ${mid} L${x2} ${y2 + (up ? 3 : -3)}" marker-end="url(#a)"/>`);
      labels.push(`<text class="l" x="${(x1 + x2) / 2}" y="${mid - 4}" text-anchor="middle">${esc(r.col)}</text>`);
    }
  }
  // 高度按**实际画到哪**算：同层绕线会伸到最后一层框的下面，
  // 用 y-VGAP 会把它切掉
  const bottom = Math.max(y - VGAP + 26, maxDraw + 14, 90);
  return head(W, bottom, `${domainLabel} 表关系`,
    `${names.length} 张表，箭头由引用方指向被引用方。`) +
    arrows.join("\n") + "\n" + boxes.join("\n") + "\n" + labels.join("\n") + "</svg>";
}
