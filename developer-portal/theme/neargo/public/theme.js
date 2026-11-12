// Run before the stylesheet to apply the saved preference without a color flash.
(() => {
  const root = document.documentElement;
  const system = matchMedia('(prefers-color-scheme: dark)');
  const modes = ['system', 'light', 'dark'];
  const accents = ['neutral', 'blue', 'violet', 'emerald', 'orange'];
  let mode = 'system';
  let accent = 'neutral';
  try {
    const saved = JSON.parse(localStorage.getItem('neargo-docs-theme') || '{}');
    if (modes.includes(saved.mode)) mode = saved.mode;
    if (accents.includes(saved.accent)) accent = saved.accent;
  } catch { /* Storage may be disabled; the default theme remains usable. */ }
  function apply() {
    root.dataset.mode = mode === 'system' ? (system.matches ? 'dark' : 'light') : mode;
    root.dataset.accent = accent;
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', root.dataset.mode === 'dark' ? '#18181b' : '#ffffff');
  }
  apply();
  system.addEventListener('change', apply);
  document.addEventListener('DOMContentLoaded', () => {
    const modeSelect = document.querySelector('#mode-select');
    const accentSelect = document.querySelector('#accent-select');
    modeSelect.value = mode;
    accentSelect.value = accent;
    function update() {
      mode = modeSelect.value;
      accent = accentSelect.value;
      apply();
      try { localStorage.setItem('neargo-docs-theme', JSON.stringify({ mode, accent })); }
      catch { /* Theme selection still works for the current page. */ }
    }
    modeSelect.addEventListener('change', update);
    accentSelect.addEventListener('change', update);
  });
})();
