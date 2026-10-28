import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';

it('opens the workbench first and keeps the print center as a secondary route', () => {
 const pages = JSON.parse(readFileSync(new URL('../src/pages.json', import.meta.url), 'utf8')).pages as {path:string}[];
 expect(pages[0].path).toBe('pages/home/index');
 expect(pages.some(page => page.path === 'pages/print-center/index')).toBe(true);
});
