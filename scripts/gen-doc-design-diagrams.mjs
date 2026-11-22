#!/usr/bin/env node
/** Render archived design intent as portable SVG; JSON preserves every original node/edge. */
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { basename, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const diagrams = JSON.parse(readFileSync(join(root, 'scripts/doc-design-diagrams.json'), 'utf8'));
const scratch = mkdtempSync(join(tmpdir(), 'doc-design-diagrams-'));
const config = join(scratch, 'config.json');
writeFileSync(config, JSON.stringify({
  theme: 'neutral', deterministicIds: true,
  fontFamily: 'PingFang SC, Noto Sans CJK SC, sans-serif',
  flowchart: { htmlLabels: false, useMaxWidth: false },
  class: { htmlLabels: false, useMaxWidth: false },
  sequence: { useMaxWidth: false, wrap: true, width: 220 },
}));
const xml = (s) => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
try {
  for (const [i, diagram] of diagrams.entries()) {
    const input = join(scratch, `${i}.mmd`);
    const output = join(root, diagram.output);
    // SVG text labels do not interpret HTML emphasis; keep source intact in JSON.
    const source = diagram.source.replace(/<\/?b>/g, '').replace(/ +%%[^\n]*/g, '');
    writeFileSync(input, source);
    mkdirSync(dirname(output), { recursive: true });
    // Fixed renderer version keeps new downloads from silently changing layout.
    execFileSync('npx', ['--yes', '--package', '@mermaid-js/mermaid-cli@12.2.0', 'mmdc',
      '-i', input, '-o', output, '-I', basename(output, '.svg'), '-c', config, '--no-font-embed', '-q'], { stdio: 'inherit' });
    let svg = readFileSync(output, 'utf8');
    svg = svg.replace(/<svg\b[^>]*>/, (tag) => `${tag}<title>${xml(diagram.title)}</title><desc>${xml(diagram.description)}</desc>`);
    writeFileSync(output, svg);
    console.log(`${i + 1}/${diagrams.length} ${diagram.output}`);
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
