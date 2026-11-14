#!/usr/bin/env node
// Shares the forward owner-delta renderer; no historical migrations are changed.
import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { render } from './gen-service-execution-start-schema.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));
const read = path => JSON.parse(readFileSync(resolve(root, path), 'utf8'));
const rendered = render(read('backend/shop-core/src/main/schema/service-execution-completion-schema.json'),
  read('backend/shop-core/src/main/resources/META-INF/shop/service-execution-start-schema.json'));
for (const [path, content] of [
  ['backend/shop-app/src/main/resources/db/migration/V669__service_execution_completion.sql', rendered.sql],
  ['backend/shop-core/src/main/resources/META-INF/shop/service-execution-completion-schema.json',
    JSON.stringify(rendered.descriptor, null, 2) + '\n'],
]) {
  if (process.argv.includes('--check')) {
    if (readFileSync(resolve(root, path), 'utf8') !== content) throw new Error(`Stale generated file: ${path}`);
  } else writeFileSync(resolve(root, path), content);
}
