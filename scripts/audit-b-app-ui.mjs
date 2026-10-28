/** B 端源码盘点；只输出审查资料，不改页面、词条或现有 UI 规范真源。 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { parse } from '@vue/compiler-sfc';
import { baseParse } from '@vue/compiler-dom';
import postcss from 'postcss';
import { uiPolicyViolations } from './lib/b-app-ui-policy.mjs';
import { translationCalls } from './lib/b-app-i18n-refs.mjs';
import { declaredCssClasses } from './lib/b-app-css-classes.mjs';
import { inspectLayout } from './lib/b-app-layout-contracts.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'docs/technical/design/reviews/b-app-ui');
const read = f => fs.readFileSync(path.join(root, f), 'utf8');
const walk = dir => fs.readdirSync(path.join(root, dir), { withFileTypes: true })
  .sort((a, b) => a.name.localeCompare(b.name, 'en'))
  .flatMap(e => e.isDirectory() ? walk(`${dir}/${e.name}`) : [`${dir}/${e.name}`]);
const lineAt = (s, offset) => s.slice(0, offset).split('\n').length;
const literal = n => n && (ts.isStringLiteral(n) || ts.isNoSubstitutionTemplateLiteral(n)) ? n.text : null;
const source = (s, f = 'audit.ts') => ts.createSourceFile(f, s, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
const visit = (n, cb) => { cb(n); ts.forEachChild(n, child => visit(child, cb)); };
const locales = {};
for (const lang of ['zh-CN', 'en', 'ar']) {
  const file = `b-app/src/i18n/locale/${lang}.ts`;
  const ast = source(read(file));
  const flat = {};
  function flatten(n, prefix = '') {
    if (ts.isObjectLiteralExpression(n)) {
      for (const p of n.properties) {
        if (!ts.isPropertyAssignment(p)) throw new Error(`Unsupported locale property: ${file}`);
        const key = p.name.text;
        flatten(p.initializer, prefix ? `${prefix}.${key}` : key);
      }
    } else {
      const value = literal(n);
      if (value === null) throw new Error(`Unsupported locale value: ${file}/${prefix}`);
      if (flat[prefix]) throw new Error(`Duplicate locale key: ${file}/${prefix}`);
      flat[prefix] = { value, line: ast.getLineAndCharacterOfPosition(n.getStart(ast)).line + 1 };
    }
  }
  const exp = ast.statements.find(ts.isExportAssignment);
  if (!exp) throw new Error(`Missing locale export: ${file}`);
  flatten(exp.expression);
  locales[lang] = flat;
}

const refs = [], dynamic = [], hardcoded = [], layouts = [], files = [], unstyledControls = [];
const addRef = (key, file, line, kind) => refs.push({ key, file, line, kind });
function expressions(code, file, line, collectLiterals = false) {
  const ast = source(code);
  visit(ast, n => {
    const value = ts.isTemplateExpression(n) ? n.getText(ast) : literal(n);
    if (collectLiterals && value && /\p{Script=Han}/u.test(value)) {
      hardcoded.push({ file, line: line + ast.getLineAndCharacterOfPosition(n.getStart(ast)).line, kind: 'expression-string', text: value });
    }
  });
  for (const call of translationCalls(code)) {
    const at = line + call.line;
    if (call.key !== null) addRef(call.key, file, at, 'call');
    else dynamic.push({ file, line: at, expression: call.expression });
  }
}
const vueFiles = [...walk('b-app/src'), ...walk('packages/ui/src/components')].filter(f => f.endsWith('.vue'));
for (const file of vueFiles) {
  const text = read(file);
  const { descriptor, errors } = parse(text, { filename: file });
  if (errors.length) throw new Error(`${file}: ${errors.join(', ')}`);
  const tags = {}, classes = {}, props = [], propNames = [];
  for (const block of [descriptor.script, descriptor.scriptSetup].filter(Boolean)) {
    expressions(block.content, file, block.loc.start.line);
    const ast = source(block.content);
    visit(ast, n => {
      const value = ts.isTemplateExpression(n) ? n.getText(ast) : literal(n);
      if (value && /\p{Script=Han}/u.test(value)) hardcoded.push({ file, line: block.loc.start.line + ast.getLineAndCharacterOfPosition(n.getStart(ast)).line, kind: 'script-string', text: value });
      if (ts.isCallExpression(n) && n.expression.getText(ast) === 'defineProps') {
        const type = n.typeArguments?.[0];
        if (type && ts.isTypeLiteralNode(type)) for (const p of type.members) { props.push(p.getText(ast)); if (p.name) propNames.push(p.name.getText(ast).replace(/['\"]/g, '')); }
      }
      // UI API 的直接字面量；其他脚本文本不一概当成用户可见文案。
      if (ts.isCallExpression(n) && /^(uni\.showToast|uni\.showModal|confirm|prompt)$/.test(n.expression.getText(ast))) {
        visit(n, child => {
          if (ts.isPropertyAssignment(child) && /^(title|content|message|confirmText|cancelText)$/.test(child.name.getText(ast))) {
            const value = literal(child.initializer);
            if (value && /\p{Script=Han}/u.test(value)) hardcoded.push({ file, line: block.loc.start.line + ast.getLineAndCharacterOfPosition(child.getStart(ast)).line, kind: 'ui-api', text: value });
          }
        });
      }
    });
  }
  let roots = [];
  if (descriptor.template) {
    const ast = baseParse(descriptor.template.content);
    roots = ast.children.filter(n => n.type === 1).map(n => n.tag);
    function template(n) {
      const line = descriptor.template.loc.start.line + (n.loc?.start.line ?? 1) - 1;
      if (n.type === 1) {
        tags[n.tag] = (tags[n.tag] ?? 0) + 1;
        if (['input', 'textarea', 'button'].includes(n.tag) && !n.props.some(p => p.type === 6 && p.name === 'class' && p.value?.content.trim())) {
          unstyledControls.push({ file, line, tag: n.tag });
        }
        for (const p of n.props) {
          const at = descriptor.template.loc.start.line + p.loc.start.line - 1;
          if (p.type === 6) {
            if (p.name === 'class') for (const c of (p.value?.content ?? '').split(/\s+/).filter(Boolean)) classes[c] = (classes[c] ?? 0) + 1;
            if (p.name === 'title-key' && p.value) addRef(p.value.content, file, at, 'title-key');
            if (p.value && /\p{Script=Han}/u.test(p.value.content) && !['class', 'style'].includes(p.name)) hardcoded.push({ file, line: at, kind: `attribute:${p.name}`, text: p.value.content });
          } else if (p.type === 7 && p.exp) {
            expressions(p.exp.content, file, at, true);
            if (p.name === 'bind' && p.arg?.content === 'title-key') {
              visit(source(p.exp.content), s => { const key = literal(s); if (key !== null && locales['zh-CN'][key]) addRef(key, file, at, 'bound-title-key'); });
            }
          }
        }
      } else if (n.type === 2 && /\p{Script=Han}/u.test(n.content)) {
        hardcoded.push({ file, line, kind: 'template-text', text: n.content.trim() });
      } else if (n.type === 5) expressions(n.content.content, file, line, true);
      for (const child of n.children ?? []) template(child);
    }
    template(ast);
  }
  let cssLines = 0;
  for (const style of descriptor.styles) {
    cssLines += style.content.split('\n').filter(l => l.trim()).length;
    const ast = postcss.parse(style.content);
    ast.walkDecls(d => {
      const item = { file, line: style.loc.start.line + d.source.start.line - 1, selector: d.parent.selector ?? '', property: d.prop, value: d.value };
      if (d.prop === 'position' && d.value === 'fixed') layouts.push({ rule: 'custom-fixed', ...item });
      if (/^(padding|margin|gap|row-gap|column-gap)(-|$)/.test(d.prop)) {
        const values = [...d.value.matchAll(/(-?\d+(?:\.\d+)?)rpx/g)].map(m => Number(m[1]));
        if (values.some(v => v % 4 !== 0)) layouts.push({ rule: 'off-grid-spacing', ...item });
      }
      if (/\.(?:field(?:__\w+)?|sh-(?:card|row|block|cells|btn|notice))(?![\w-])/.test(item.selector)) layouts.push({ rule: 'shared-class-override', ...item });
    });
  }
  files.push({ file, roots, tags, classes, props, propNames, lines: text.split('\n').length, cssNonblankLines: cssLines });
}
for (const file of walk('b-app/src').filter(f => f.endsWith('.ts') && !f.includes('/i18n/locale/'))) expressions(read(file), file, 1);
const unique = list => [...new Map(list.map(x => [JSON.stringify(x), x])).values()];
const styleSources = [read('packages/ui/src/styles/base.css'), ...vueFiles.map(f => {
  const { descriptor } = parse(read(f));
  return descriptor.styles.map(s => s.content).join('\n');
})].join('\n');
const declaredClasses = declaredCssClasses(styleSources);
const unresolvedClasses = files.flatMap(f => Object.keys(f.classes).filter(c => /^(sh-|txt-|field(?:__|-)|is-)/.test(c) && !declaredClasses.has(c)).map(className => ({ file: f.file, className })));
const literalSites = new Map();
for (const item of hardcoded) {
  const key = JSON.stringify([item.file, item.line, item.text]);
  if (!literalSites.has(key)) literalSites.set(key, item);
}
const references = unique(refs), dynamicReferences = unique(dynamic), literals = [...literalSites.values()];
const componentContracts = Object.fromEntries(files.filter(f => f.file.includes('/components/')).flatMap(f => {
 const name = path.basename(f.file, '.vue');
 return [[name, f.propNames], [name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase(), f.propNames]];
}));
const globalLayoutStyles = read('packages/ui/src/styles/base.css') + '\n' + parse(read('b-app/src/App.vue')).descriptor.styles.map(s => s.content).join('\n');
const layoutInspections = vueFiles.filter(f => f.startsWith('b-app/')).map(file => ({ file, ...inspectLayout({ file, source: read(file), globalStyles: globalLayoutStyles, contracts: componentContracts }) }));
const layoutContractViolations = layoutInspections.flatMap(r => r.violations);
const layoutReviewCandidates = layoutInspections.flatMap(r => r.candidates);

const refsFor = key => references.filter(r => r.key === key);
const allKeys = [...new Set(Object.values(locales).flatMap(entries => Object.keys(entries)))];
const catalog = allKeys.map(key => ({
  key, translations: Object.fromEntries(Object.entries(locales).map(([lang, entries]) => [lang, entries[key]?.value ?? null])),
  sources: Object.fromEntries(Object.entries(locales).map(([lang, entries]) => [lang, { file: `b-app/src/i18n/locale/${lang}.ts`, line: entries[key]?.line ?? null }])),
  references: refsFor(key),
}));
const groups = new Map();
for (const c of catalog) { const value = c.translations['zh-CN']; if (value === null) continue; if (!groups.has(value)) groups.set(value, []); groups.get(value).push(c.key); }
const duplicates = [...groups].filter(([, keys]) => keys.length > 1).map(([text, keys]) => ({
  text, keys, canonicalCandidate: keys.find(k => k.startsWith('common.')) ?? null,
  sameInAllLanguages: ['en', 'ar'].every(lang => new Set(keys.map(k => locales[lang][k]?.value)).size === 1),
})).sort((a, b) => b.keys.length - a.keys.length || a.keys[0].localeCompare(b.keys[0], 'en'));
const placeholders = v => [...v.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort().join(',');
const parity = ['en', 'ar'].map(lang => ({ lang,
  missing: Object.keys(locales['zh-CN']).filter(k => !locales[lang][k]),
  extra: Object.keys(locales[lang]).filter(k => !locales['zh-CN'][k]),
  placeholderMismatch: catalog.filter(c => c.translations['zh-CN'] !== null && c.translations[lang] !== null && placeholders(c.translations['zh-CN']) !== placeholders(c.translations[lang])).map(c => c.key),
}));
const pagesConfig = JSON.parse(read('b-app/src/pages.json'));
const registered = [...pagesConfig.pages.map(p => p.path), ...(pagesConfig.subPackages ?? []).flatMap(p => p.pages.map(x => `${p.root}/${x.path}`))];
const pages = files.filter(f => f.file.startsWith('b-app/src/pages/')).map(f => ({ ...f,
  registered: registered.includes(f.file.replace('b-app/src/', '').replace(/\.vue$/, '')),
  referenceCount: references.filter(r => r.file === f.file).length,
  literalCount: literals.filter(r => r.file === f.file).length,
  layoutCandidateCount: layouts.filter(r => r.file === f.file).length,
}));
const kebab = s => s.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase();
const uiLib = JSON.parse(read('docs/technical/design/ui-lib.json'));
const components = files.filter(f => f.file.includes('/components/')).map(f => {
  const name = path.basename(f.file, '.vue');
  const names = [name, kebab(name)];
  const uses = files.flatMap(x => Object.entries(x.tags).filter(([tag]) => names.includes(tag)).map(([tag, count]) => ({ file: x.file, tag, count })));
  return { ...f, name, description: name === 'biz-rule-help' ? '按需打开的规则说明（复用 sh-sheet）' : uiLib.components.find(c => c.file === f.file)?.note ?? '', uses,
    pageCount: new Set(uses.filter(x => x.file.startsWith('b-app/src/pages/')).map(x => x.file)).size,
    pageInstances: uses.filter(x => x.file.startsWith('b-app/src/pages/')).reduce((n, x) => n + x.count, 0),
  };
});
const nativeTags = new Set(['view', 'text', 'button', 'picker', 'input', 'textarea', 'scroll-view', 'image', 'switch', 'map', 'cover-view']);
const nativeComponents = [...nativeTags].map(name => ({ name,
  pageCount: pages.filter(p => p.tags[name]).length,
  instances: pages.reduce((n, p) => n + (p.tags[name] ?? 0), 0),
})).filter(c => c.instances);
const publicClassNames = new Set();
postcss.parse(read('packages/ui/src/styles/base.css')).walkRules(r => {
  for (const m of r.selector.matchAll(/\.((?:sh-|txt-|field)[\w-]*)/g)) publicClassNames.add(m[1]);
});
const publicClasses = [...publicClassNames].sort().map(name => ({ name,
  pageCount: pages.filter(p => p.classes[name]).length,
  instances: pages.reduce((n, p) => n + (p.classes[name] ?? 0), 0),
}));
const longCopy = catalog.filter(c => c.translations['zh-CN'] !== null && [...c.translations['zh-CN']].length > 36).map(c => ({ key: c.key, text: c.translations['zh-CN'], length: [...c.translations['zh-CN']].length, references: c.references }));
const report = {
  scope: '当前工作区 B 端全部 Vue/TS、共享 UI Vue、三语词库及路由；静态盘点，未做运行时视觉验收。',
  limits: ['动态 key 不推断为缺失或未使用；无静态引用不等于孤儿。', '直写中文覆盖 Vue 模板/表达式/脚本字符串；script-string 也可能是日志、默认值或内部数据，须人工判断。服务端、外部共享工具、原生插件与英文直写不保证穷尽。', '重复中文不等于业务同义；布局候选不等于已确认缺陷。', '组件用量为模板静态引用；不含运行时动态组件与循环实际渲染次数。'],
  counts: { pages: pages.length, registeredRoutes: registered.length, components: components.length, localeKeys: Object.keys(locales['zh-CN']).length, catalogEntries: catalog.length, references: references.length, dynamicReferences: dynamicReferences.length, duplicateGroups: duplicates.length, duplicateKeys: duplicates.reduce((n, d) => n + d.keys.length, 0), literalCandidates: literals.length, longCopyCandidates: longCopy.length, layoutCandidates: layouts.length },
  parity, missingStaticKeys: references.filter(r => !locales['zh-CN'][r.key]), dynamicReferences,
  duplicateGroups: duplicates, longCopyCandidates: longCopy, literalCandidates: literals, layoutCandidates: layouts,
  unresolvedClasses, unstyledControls, layoutContractViolations, layoutReviewCandidates, nativeComponents, publicClasses, pages, components,
};
if (process.argv.includes('--check')) {
  const exceptions = JSON.parse(read('b-app/scripts/ui-exceptions.json'));
  const errors = uiPolicyViolations(report, exceptions);
  if (errors.length) {
    console.error(errors.join('\n'));
    process.exit(1);
  }
  console.log(`B UI check passed: ${pages.length} pages, ${components.length} components; translations, references, copy, controls and layout.`);
  process.exit(0);
}
fs.mkdirSync(out, { recursive: true });
fs.writeFileSync(path.join(out, 'copy-catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
fs.writeFileSync(path.join(out, 'audit.json'), JSON.stringify(report, null, 2) + '\n');
const cell = v => String(v).replaceAll('|', '\\|').replaceAll('\n', ' ');
const table = (headers, rows) => [headers, headers.map(() => '---'), ...rows].map(r => `| ${r.map(cell).join(' | ')} |`).join('\n');
const md = [
  '# B 端 UI 全量源码清单',
  '> 状态：当前工作区的源码盘点；实施与验收结果见 review.md。由 `node scripts/audit-b-app-ui.mjs` 生成，勿手改。',
  '## 范围与口径', report.scope, ...report.limits.map(x => `- ${x}`),
  '共享 UI 的引用也参与词条分析；C 端页面不参与统计。行号对应生成时工作区，源码改动后请重新运行。',
  '完整三语文案及静态引用见 [copy-catalog.json](./copy-catalog.json)；候选项、组件 props/依赖/使用位置与每页 CSS 规模见 [audit.json](./audit.json)。',
  table(['项目', '数量'], Object.entries(report.counts)),
  '## 页面清单',
  table(['源码页面', '已注册', '直接使用的组件（模板次数）', '静态文案引用', '直写中文候选', '布局候选'], pages.map(p => [p.file.replace('b-app/src/pages/', ''), p.registered ? '是' : '否', Object.entries(p.tags).filter(([t]) => /^(sh-|biz-|app-|StorePoint)/.test(t)).map(([t, n]) => `${t} ×${n}`).join('、'), p.referenceCount, p.literalCount, p.layoutCandidateCount])),
  '## 组件清单',
  '页数/次数均指 B 端页面直接使用；间接依赖另列，以免把外壳内部组件误报为未使用。props 以 audit.json 中从源码提取的声明为准；用途说明引用现有 ui-lib.json，空白项需人工补充。',
  table(['组件', '用途', '源码', '直接页面数/次数', '内部组件依赖'], components.map(c => [c.name, c.description || '应用常驻层', c.file, `${c.pageCount}/${c.pageInstances}`, Object.keys(c.tags).filter(t => /^(sh-|biz-|app-)/.test(t)).join('、')])),
  '## 原生组件',
  table(['组件', '直接页面数', '模板次数'], nativeComponents.map(c => [c.name, c.pageCount, c.instances])),
  '## 全局公共类',
  '仅统计 base.css 的声明及页面静态 class 属性；动态 :class 和 UnoCSS 工具类不计入次数。B 端 .field 的间距覆盖在 App.vue。',
  table(['公共类', '直接页面数', '静态使用次数'], publicClasses.map(c => [c.name, c.pageCount, c.instances])),
  '## 重复中文分组（待语义审查）',
  '共用 common key 的候选优先排列；三语相同仍需核对动作、对象与上下文，禁止批量按字符串合并。',
  table(['中文', 'keys', '已有通用 key', '三语值均相同'], duplicates.map(d => [d.text, d.keys.join('、'), d.canonicalCandidate ?? '—', d.sameInAllLanguages ? '是' : '否'])),
  '## 直写中文候选',
  table(['位置', '类型', '内容'], literals.map(r => [`${r.file}:${r.line}`, r.kind, r.text])),
  '## 未找到样式声明的 sh-* 类（待核对 UnoCSS 与运行时注入）',
  table(['源码', '类名'], unresolvedClasses.map(c => [c.file, c.className])),
  '## 三语一致性',
  table(['语言', '缺 key', '多 key', '占位符差异'], parity.map(p => [p.lang, p.missing.length, p.extra.length, p.placeholderMismatch.length])),
  `静态引用未命中中文词库：${report.missingStaticKeys.length} 处。动态引用：${dynamicReferences.length} 处，详情见 audit.json。`,
].join('\n\n') + '\n';
fs.writeFileSync(path.join(out, 'inventory.md'), md);
console.log(JSON.stringify({ ...report.counts, parity, missingStaticKeys: report.missingStaticKeys }, null, 2));
