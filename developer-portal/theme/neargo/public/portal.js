const english = document.documentElement.lang === 'en';
const copy = english ? { ready: 'Copy', done: 'Copied', manual: 'Select to copy' } : { ready: '复制', done: '已复制', manual: '请选中复制' };
const toc = document.querySelector('#page-toc');
for (const heading of document.querySelectorAll('.prose h2, .prose h3')) {
  if (!heading.id) heading.id = `section-${toc.children.length + 1}`;
  const link = document.createElement('a');
  link.href = `#${heading.id}`;
  link.textContent = heading.textContent;
  link.className = heading.tagName === 'H3' ? 'subheading' : '';
  toc.append(link);
}
// Native details works without JavaScript; collapse the long menu on small screens.
if (matchMedia('(max-width: 760px)').matches) document.querySelector('.mobile-nav').open = false;
for (const block of document.querySelectorAll('.prose pre')) {
  const button = document.createElement('button');
  button.className = 'copy-code';
  button.textContent = copy.ready;
  button.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(block.querySelector('code')?.textContent || ''); button.textContent = copy.done; }
    catch { button.textContent = copy.manual; }
    setTimeout(() => { button.textContent = copy.ready; }, 1800);
  });
  block.append(button);
}
