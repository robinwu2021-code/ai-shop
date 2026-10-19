// Convert the deliberately small points diagram grammar using the repository's
// SVG layout engine. Unknown source lines fail instead of silently dropping nodes.
import { readFileSync, writeFileSync } from 'node:fs';
import { tableGraph } from './lib/svg-erd.mjs';
const dir = new URL('../docs/technical/diagrams/', import.meta.url);
for (const name of ['points-operations-1', 'points-operations-2', 'points-order-integration-1']) {
  const source = readFileSync(new URL(name + '.mmd', dir), 'utf8');
  const nodes = new Map(), relations = [];
  function node(raw) {
    const match = /^(?:\|([^|]+)\|)?\s*(\w+)(?:\[(.*)\]|\{(.*)\})?$/.exec(raw.trim());
    if (!match) throw Error(`Unrecognized node: ${raw}`);
    const [, label, id, box, decision] = match;
    if (!nodes.has(id)) nodes.set(id, { name: id, comment: '' });
    if (box || decision) nodes.get(id).comment = box || decision;
    return { id, label };
  }
  for (const line of source.trim().split('\n').slice(1)) {
    if (!line.trim()) continue;
    const members = /^\s*(\w+)\s*:\s*(.+)$/.exec(line);
    if (members) {
      node(members[1]);
      nodes.get(members[1]).comment += (nodes.get(members[1]).comment ? '; ' : '') + members[2];
      continue;
    }
    const parts = line.trim().split(/\s*(-->|\.\.\|>)\s*/);
    if (parts.length < 3) throw Error(`Unrecognized edge: ${line}`);
    let previous = node(parts[0]);
    for (let index = 1; index < parts.length; index += 2) {
      const next = node(parts[index + 1]);
      relations.push({ from: previous.id, to: next.id,
        col: next.label || (parts[index] === '..|>' ? '实现' : '') });
      previous = next;
    }
  }
  // Labels are the actual flow nodes; class diagrams already use canonical names.
  const flow = source.startsWith('flowchart');
  const labels = new Map([...nodes].map(([id,n]) => [id, n.comment && flow ? `${id} ${n.comment}` : id]));
  const tables = [...nodes].map(([id,n]) => ({ name: labels.get(id), comment: flow ? '' : n.comment }));
  const edges = relations.map(r => ({ ...r, from: labels.get(r.from), to: labels.get(r.to) }));
  let svg = tableGraph('积分模块', tables, edges).replaceAll('表关系', '流程与依赖').replace('张表', '个节点');
  if (flow) {
    const esc = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    const positions = new Map([...nodes.keys()].map((id, i) => [id, { y: 35 + i * 105, index: i }]));
    const height = nodes.size * 105 + 60;
    const paths = [], captions = [], boxes = [];
    for (const [index, edge] of relations.entries()) {
      const a = positions.get(edge.from), b = positions.get(edge.to);
      let path, labelX, labelY;
      if (a.index + 1 === b.index) {
        path = `M500 ${a.y + 58} V${b.y}`; labelX = 510; labelY = a.y + 83;
      } else {
        const left = index % 2 === 0, x = left ? 60 + index % 7 * 18 : 940 - index % 7 * 18;
        const side = left ? 230 : 770;
        const start = a.y + 15 + index % 3 * 12, end = b.y + 15 + index % 3 * 12;
        path = `M${side} ${start} H${x} V${end} H${side}`;
        labelX = left ? 5 : 785; labelY = start - 4;
      }
      paths.push(`<path d="${path}" fill="none" stroke="#8295a7" stroke-width="1.4" marker-end="url(#arrow)"/>`);
      if (edge.col) captions.push(`<text x="${labelX}" y="${labelY}" font-size="12" fill="#35495c">${esc(edge.col)}</text>`);
    }
    for (const [id, n] of nodes) {
      const { y } = positions.get(id);
      boxes.push(`<rect x="230" y="${y}" width="540" height="58" rx="8" fill="#eaf1f6" stroke="#8fb3c9"/><text x="500" y="${y + 34}" text-anchor="middle" font-size="15" fill="#161a21">${esc(id + ' ' + n.comment)}</text>`);
    }
    svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="${height}" viewBox="0 0 1000 ${height}" role="img" font-family="system-ui,-apple-system,PingFang SC,sans-serif"><title>积分流程</title><desc>按箭头追踪门店参与、原资金及售后责任；回边表示恢复或重新接线，不创建新的资金请求。</desc><defs><marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10" fill="none" stroke="#8295a7"/></marker></defs><rect width="100%" height="100%" fill="white"/>${paths.join('')}${boxes.join('')}${captions.join('')}</svg>`;
  }
  writeFileSync(new URL(name + '.svg', dir), svg);
  console.log(`${name}: ${nodes.size} nodes / ${relations.length} edges`);
}
