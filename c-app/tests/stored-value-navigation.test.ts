import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const config = JSON.parse(readFileSync('src/pages.json', 'utf8'));
describe('stored value mini program navigation', () => {
  for (const path of ['pages/stored-value/index', 'pages/stored-value-records/index']) {
    it(`${path} keeps native WeChat navigation and custom H5/App navigation`, () => {
      const style = config.pages.find((page: { path: string }) => page.path === path).style;
      expect(style.navigationStyle).not.toBe('custom');
      expect(style['app-plus'].navigationStyle).toBe('custom');
      expect(style.h5.navigationStyle).toBe('custom');
    });
  }
});
