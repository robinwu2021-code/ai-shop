import { parse } from '@vue/compiler-sfc';
import { baseParse } from '@vue/compiler-dom';
import postcss from 'postcss';

const classes = n => (n.props?.find(p => p.type === 6 && p.name === 'class')?.value?.content ?? '').split(/\s+/).filter(Boolean);
const has = (n, name) => classes(n).includes(name);
const directive = (n, name) => n.props?.some(p => p.type === 7 && p.name === name);
const camel = name => name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
const framework = name => ['class','style','id','key','ref','slot','ref_for','ref_key'].includes(name) || /^(data-|aria-)/.test(name);
const positive = value => value != null && (/\b(?:var|calc)\(/.test(value) || value.split(/\s+/).some(x => Number.parseFloat(x) > 0));
const nonzero = value => value != null && (/\b(?:var|calc)\(/.test(value) || value.split(/\s+/).some(x => Number.isFinite(Number.parseFloat(x)) && Number.parseFloat(x) !== 0));
const parts = value => postcss.list.space(value);
function side(value, edge) {
 const p = parts(value);
 return edge === 'top' ? p[0] : p.length > 2 ? p[2] : p[0];
}
function simpleMatch(selector, node) {
 if (selector.includes(':') || /[\[\]*+~]/.test(selector)) return false;
 const names = [...selector.matchAll(/\.([\w-]+)/g)].map(x => x[1]);
 const tag = /^[\w-]+/.exec(selector)?.[0];
 return (!tag || node.tag === tag) && names.every(c => has(node, c)) && Boolean(tag || names.length);
}
function selectorMatch(selector, node, ancestors) {
 const chunks = selector.trim().match(/[^>\s]+|>/g) ?? [];
 if (!simpleMatch(chunks.pop() ?? '', node)) return false;
 let at = ancestors.length - 1;
 while (chunks.length) {
  const direct = chunks[chunks.length - 1] === '>';
  if (direct) chunks.pop();
  const chunk = chunks.pop();
  if (!direct) while (at >= 0 && !simpleMatch(chunk, ancestors[at])) at--;
  if (at < 0 || !simpleMatch(chunk, ancestors[at])) return false;
  at--;
 }
 return true;
}

function declarationValue(d, prop) {
 if (d.prop === prop) return d.value;
 if (/^(row|column)-gap$/.test(prop) && d.prop === 'gap') {
  const p = parts(d.value);
  return prop === 'row-gap' ? p[0] : p[p.length - 1];
 }
 if (/^(margin|padding)-(top|bottom)$/.test(prop)) {
  const [kind, edge] = prop.split('-');
  if (d.prop === kind) return side(d.value, edge);
  if (d.prop === kind + '-block') {
   const p = parts(d.value);
   return edge === 'top' ? p[0] : p[p.length - 1];
  }
 }
 return undefined;
}
const floating = n => ['sh-sheet','sh-dialog','sh-actionbar','sh-savebar','sh-prompt','sh-theme-sheet','sh-fab'].includes(n.tag);
const rendered = nodes => nodes.filter(n => n.type === 1 || n.type === 5 || n.type === 2 && n.content.trim());
const emptySummary = () => ({ first: new Set(), last: new Set(), empty: true, pairs: [] });
const union = sets => new Set(sets.flatMap(s => [...s]));
function choices(options) {
 return { first: union(options.map(o => o.first)), last: union(options.map(o => o.last)), empty: options.some(o => o.empty), pairs: options.flatMap(o => o.pairs) };
}
function concatenate(left, right) {
 return {
  first: left.empty ? union([left.first, right.first]) : left.first,
  last: right.empty ? union([left.last, right.last]) : right.last,
  empty: left.empty && right.empty,
  pairs: [...left.pairs, ...right.pairs, ...[...left.last].flatMap(a => [...right.first].map(b => [a, b]))],
 };
}
/** Keep possible first/last rendered nodes instead of enumerating branch combinations. */
function boundaries(children) {
 const nodes = rendered(children);
 let result = emptySummary();
 for (let i = 0; i < nodes.length; i++) {
  const node = nodes[i];
  let next;
  if (directive(node, 'if')) {
   const options = [nodeBoundary(node)];
   let complete = false;
   while (nodes[i + 1] && (directive(nodes[i + 1], 'else-if') || directive(nodes[i + 1], 'else'))) {
    const branch = nodes[++i];
    options.push(nodeBoundary(branch));
    if (directive(branch, 'else')) { complete = true; break; }
   }
   if (!complete) options.push(emptySummary());
   next = choices(options);
  } else next = nodeBoundary(node);
  result = concatenate(result, next);
 }
 return result;
}
function nodeBoundary(node) {
 // Named slots belong to the component's own layout region, not its default slot.
 if (floating(node) || directive(node, 'slot')) return emptySummary();
 let result = node.tag === 'template' ? boundaries(node.children ?? []) : { first: new Set([node]), last: new Set([node]), empty: false, pairs: [] };
 if (directive(node, 'for')) result = { ...result, empty: true, pairs: [...result.pairs, ...[...result.last].flatMap(a => [...result.first].map(b => [a, b]))] };
 return result;
}
function directElements(children) {
 return children.filter(n => n.type === 1).flatMap(n => n.tag === 'template' && !directive(n, 'slot') ? directElements(n.children ?? []) : [n]);
}

/** Declared composition checks only; media queries, native rendering and real geometry still need browser/device checks. */
export function inspectLayout({ source, file, globalStyles = '', contracts = {} }) {
 const { descriptor, errors } = parse(source, { filename: file });
 if (errors.length) throw errors[0];
 const violations = [], candidates = [];
 if (!descriptor.template) return { violations, candidates };
 const ast = baseParse(descriptor.template.content);
 const css = postcss.parse([globalStyles, ...descriptor.styles.map(s => s.content)].join('\n'));
 const rules = [];
 css.walkRules(rule => rules.push({ selectors: rule.selector.split(','), declarations: rule.nodes.filter(n => n.type === 'decl') }));
 function value(node, ancestors, prop) {
  let result, priority = -1;
  for (const rule of rules) {
   const matches = rule.selectors.filter(s => selectorMatch(s, node, ancestors));
   if (!matches.length) continue;
   const score = Math.max(...matches.map(s => [...s.matchAll(/\.[\w-]+/g)].length * 10 + (s.match(/(?:^|[ >])[a-z][\w-]*/g) ?? []).length));
   if (score < priority) continue;
   for (const d of rule.declarations) {
    const candidate = declarationValue(d, prop);
    if (candidate !== undefined) { result = candidate; priority = score; }
   }
  }
  const inline = node.props?.find(p => p.type === 6 && p.name === 'style')?.value?.content;
  if (inline) for (const d of postcss.parse(`a{${inline}}`).first.nodes) {
   const candidate = declarationValue(d, prop);
   if (candidate !== undefined) result = candidate;
  }
  return result;
 }
 function record(list, node, rule, detail) {
  list.push({ file, line: descriptor.template.loc.start.line + node.loc.start.line - 1, rule, detail });
 }
 function unit(node) {
  if (floating(node)) return false;
  if (['input','textarea','button'].includes(node.tag) || has(node, 'sh-btn')) return true;
  return (node.children ?? []).some(n => n.type === 1 && unit(n));
 }
 function editable(node) { return ['input','textarea'].includes(node.tag) || (node.children ?? []).some(n => n.type === 1 && editable(n)); }
 function separated(node, ancestors, edge) {
  if (positive(value(node, ancestors, `margin-${edge}`))) return true;
  if (['input','textarea','button','text'].includes(node.tag) || has(node, 'sh-btn')) return false;
  if (positive(value(node, ancestors, `padding-${edge}`))) return true;
  const children = (node.children ?? []).filter(n => n.type === 1);
  const boundary = edge === 'top' ? children[0] : children[children.length - 1];
  return boundary ? separated(boundary, node.tag === 'template' ? ancestors : [...ancestors, node], edge) : false;
 }
 function visit(node, ancestors = []) {
  if (node.type !== 1 && node.type !== 0) return;
  if (node.type === 1) {
   if (node.tag === 'sh-kv' && editable(node)) record(violations, node, 'editable-kv', 'Use field groups or an explicit compound row for editable controls.');
   if (contracts[node.tag]) for (const p of node.props) {
    const name = p.type === 6 ? p.name : p.type === 7 && p.name === 'bind' && p.arg?.isStatic ? p.arg.content : null;
    if (name && !framework(name) && !contracts[node.tag].includes(camel(name))) record(violations, node, 'unknown-component-prop', `${node.tag}.${name}`);
   }
  }
  const children = (node.children ?? []).filter(n => n.type === 1);
  const transparent = node.tag === 'template';
  const path = node.type === 1 && !transparent ? [...ancestors, node] : ancestors;
  const owner = transparent ? ancestors[ancestors.length - 1] ?? node : node;
  const ownerPath = transparent ? ancestors.slice(0, -1) : ancestors;
  const dialogActions = transparent && owner.tag === 'sh-dialog' && node.props.some(p => p.type === 7 && p.name === 'slot' && p.arg?.content === 'actions');
  const layout = /^(?:inline-)?(?:flex|grid)$/.test(value(owner, ownerPath, 'display') ?? '');
  const column = /^column/.test(value(owner, ownerPath, 'flex-direction') ?? 'row');
  const axisGap = value(owner, ownerPath, column ? 'row-gap' : 'column-gap');
  const gap = dialogActions || ['sh-scaffold','sh-section'].includes(owner.tag) || layout && positive(axisGap);
  if (!transparent && (has(owner, 'sh-stack') || has(owner, 'sh-actions'))) {
   for (const child of directElements(node.children ?? [])) if (nonzero(value(child, path, 'margin-top')) || nonzero(value(child, path, 'margin-bottom'))) record(violations, child, 'mixed-spacing-owners', 'The layout group owns its gap; remove direct child vertical margins.');
  }
  // A physical parent owns all transparent-template children. Slot regions are
  // checked separately with the receiving component's spacing contract.
  if (!transparent || directive(node, 'slot')) for (const [left, right] of boundaries(node.children ?? []).pairs) {
   if (unit(left) && unit(right) && !gap && !separated(left, path, 'bottom') && !separated(right, path, 'top')) record(violations, right, 'unspaced-controls', 'Possible adjacent or repeated control groups have no declared spacing on their layout axis.');
  }
  if (has(node, 'sh-card') && children.length > 1) {
   const first = children[0], next = children[1];
   if (first.tag === 'text' && (has(first, 'txt-title') || has(first, 'txt-strong')) && !gap && !separated(first, path, 'bottom') && !separated(next, path, 'top')) record(candidates, first, 'card-heading-spacing', 'Review title/content separation; prefer sh-card__head or a stack.');
  }
  for (const child of children) visit(child, path);
 }
 visit(ast);
 const unique = list => [...new Map(list.map(r => [JSON.stringify(r), r])).values()];
 return { violations: unique(violations), candidates: unique(candidates) };
}
