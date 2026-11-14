// Share the identical dictionary key tree across languages without dropping messages.
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

export function packLocales(messages) {
  const entries = Object.entries(messages);
  if (!entries.length) throw new Error('No business locales supplied');
  let expected;
  const values = {};
  for (const [locale, tree] of entries) {
    const leaves = [];
    function visit(node) {
      if (typeof node === 'string') { leaves.push(node); return leaves.length - 1; }
      if (!node || typeof node !== 'object' || Array.isArray(node)) throw new Error(`Unsupported locale value: ${locale}`);
      return Object.fromEntries(Object.keys(node).sort().map(key => [key, visit(node[key])]));
    }
    const shape = visit(tree);
    if (expected && JSON.stringify(shape) !== JSON.stringify(expected)) throw new Error(`Business locale keys differ: ${locale}`);
    expected = shape;
    values[locale] = leaves;
  }
  return { shape: expected, values };
}

export function renderPackedLocales(messages) {
  const { shape, values } = packLocales(messages);
  return `// Generated lossless business locale data; do not edit.\n`
    + `const shape=${JSON.stringify(shape)};\nconst values=${JSON.stringify(values)};\n`
    + `function expand(node, words) {\n`
    + `  if (typeof node === "number") return words[node];\n`
    + `  const result = {};\n  for (const key of Object.keys(node)) result[key] = expand(node[key], words);\n  return result;\n}\n`
    + `const messages = {};\nfor (const locale of Object.keys(values)) messages[locale] = expand(shape, values[locale]);\nexport default messages;\n`;
}

export function writePackedLocales(directory, messages) {
  writeFileSync(join(directory, '_packed.ts'), renderPackedLocales(messages));
  for (const locale of Object.keys(messages)) {
    if (!/^[a-zA-Z-]+$/.test(locale)) throw new Error('Invalid business locale filename');
    writeFileSync(join(directory, locale + '.ts'), `import messages from "./_packed";\nexport default messages[${JSON.stringify(locale)}];\n`);
  }
}
