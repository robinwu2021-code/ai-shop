/* Legacy charge/add anchors now resolve to the single member directory. */
(() => {
 function show(){
  let id=location.hash.slice(1)||'s01';
  if(!['s01','s04'].includes(id)){location.replace('#s01');return;}
  document.querySelectorAll('figure').forEach(x=>x.classList.toggle('is-active',x.id===id));
  document.querySelectorAll('.review-links a').forEach(a=>a.setAttribute('aria-current',a.hash==='#'+id?'page':'false'));
 }
 window.addEventListener('hashchange',show);show();
})();
