import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../theme/neargo/public/theme.js', import.meta.url), 'utf8');
function browser(saved = null, blocked = false) {
  const handlers = {};
  const dataset = {};
  const controls = Object.fromEntries(['#mode-select', '#accent-select'].map(id => [id, {
    value: '', addEventListener: (_type, callback) => { handlers[id] = callback; },
  }]));
  let stored = saved;
  const system = { matches: false, addEventListener: (_event, callback) => { handlers.system = callback; } };
  vm.runInNewContext(source, {
    matchMedia: () => system,
    localStorage: {
      getItem: () => { if (blocked) throw new Error('Storage blocked'); return stored; },
      setItem: (_key, value) => { if (blocked) throw new Error('Storage blocked'); stored = value; },
    },
    document: {
      documentElement: { dataset },
      querySelector: selector => controls[selector] || { setAttribute() {} },
      addEventListener: (_event, callback) => { handlers.ready = callback; },
    },
  });
  handlers.ready();
  return { dataset, controls, handlers, system, saved: () => stored };
}

test('theme selection persists across documents and responds to system appearance', () => {
  const page = browser();
  assert.equal(page.dataset.mode, 'light');
  assert.equal(page.dataset.accent, 'neutral');
  page.controls['#accent-select'].value = 'violet';
  page.handlers['#accent-select']();
  assert.equal(page.dataset.accent, 'violet');
  const next = browser(page.saved());
  assert.equal(next.dataset.accent, 'violet');
  next.system.matches = true;
  next.handlers.system();
  assert.equal(next.dataset.mode, 'dark');
  next.controls['#mode-select'].value = 'light';
  next.handlers['#mode-select']();
  next.handlers.system();
  assert.equal(next.dataset.mode, 'light');
});

test('malformed preferences and unavailable storage retain usable theme controls', () => {
  for (const page of [browser('{bad json'), browser(null, true), browser('{"mode":"bad","accent":"bad"}')]) {
    assert.equal(page.dataset.accent, 'neutral');
    page.controls['#mode-select'].value = 'dark';
    page.handlers['#mode-select']();
    assert.equal(page.dataset.mode, 'dark');
  }
});
