/* UI prototype: in-memory fixtures only; no business APIs or real financial operations. */
(() => {
 const $=id=>document.getElementById(id);
 const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
 const members=[
  {id:'lin',name:'林女士',phone:'13900001216',balance:310,last:'2026-10-19 11:00'},
  {id:'chen',name:'陈女士',phone:'13800000628',balance:100,last:'2026-10-20 10:05'},
  {id:'zhou',name:'周先生',phone:'13700000918',balance:0,last:'2026-10-17 09:30'},
  {id:'new',name:'赵女士',phone:'13800000001',balance:null,last:null}
 ];
 let customer=members[1],chosen=null,status='IDLE',tick=0;
 let plans=[{id:'plan50',name:'充50送10',paid:50,bonus:10},{id:'plan100',name:'充100送30',paid:100,bonus:30},{id:'plan200',name:'充200送50',paid:200,bonus:50}];
 const money=n=>'¥'+n.toFixed(2),masked=c=>c.phone.slice(0,3)+'****'+c.phone.slice(-4);
 function details(){
  document.querySelectorAll('[data-customer-name]').forEach(x=>x.textContent=customer.name);
  document.querySelectorAll('[data-customer-phone]').forEach(x=>x.textContent=masked(customer));
  document.querySelectorAll('[data-balance-row]').forEach(x=>x.hidden=customer.balance===null);
  document.querySelectorAll('[data-balance]').forEach(x=>x.textContent=customer.balance===null?'':money(customer.balance));
  document.querySelectorAll('#s04 [data-record]').forEach(x=>x.textContent=customer.last?'最近储值 '+customer.last:'暂无收支记录');
 }
 function render(){
  const q=$('sv-member-search').value.trim();
  const list=(q?members.filter(c=>c.name.toLowerCase().includes(q.toLowerCase())||c.phone.startsWith(q)||c.phone.endsWith(q)):members.filter(c=>c.last)).sort((a,b)=>(b.last||'').localeCompare(a.last||'')||a.id.localeCompare(b.id));
  $('sv-member-hint').textContent=q?'':'最近储值';
  $('sv-member-list').innerHTML=list.map(c=>'<button type="button" class="card sv-member-result" data-member="'+c.id+'"><span class="hd"><b>'+esc(c.name)+'</b><span class="sv-link">详情 ›</span></span><span class="sv-split"><span class="meta">'+masked(c)+'</span>'+(c.balance===null?'':'<strong class="num">余额 '+money(c.balance)+'</strong>')+'</span>'+(c.last?'<span class="meta">最近储值 '+c.last+'</span>':'')+'</button>').join('')||'<div class="card sv-empty">'+(q?'未找到会员':'暂无储值会员')+'</div>';
 }
 function sheetOptions(){
  $('sheet-options').innerHTML=plans.map(p=>'<button class="sv-plan-option" type="button" role="radio" aria-checked="false" data-sv-plan="'+esc(p.id)+'"><span><b>'+esc(p.name)+'</b><span class="meta">到账 '+money(p.paid+p.bonus)+'</span></span><strong class="num">'+money(p.paid)+'</strong></button>').join('');
 }
 function open(){
  if(status==='PROCESSING')return;
  document.dispatchEvent(new Event('sv-plan-request'));
  chosen=null;status='IDLE';details();sheetOptions();
  document.querySelector('figure.is-active .ph').append($('recharge-overlay'));
  $('recharge-overlay').hidden=false;$('sheet-options').hidden=false;
  $('sheet-title').textContent='选择储值方案';$('sheet-hint').textContent=plans.length?'':'暂无可用方案';
  $('confirm-recharge').textContent='确认储值';$('confirm-recharge').disabled=true;
  $('close-sheet').focus();
 }
 function close(){if(status!=='PROCESSING')$('recharge-overlay').hidden=true;}
 $('member-search-form').addEventListener('submit',e=>{
  e.preventDefault();const q=$('sv-member-search').value.trim();
  if(q&&q.length<(/^[0-9]+$/.test(q)?3:2)){$('sv-member-hint').textContent='输入至少2个字或3位号码';return;}
  render();
 });
 let suggestTimer;
 $('sv-member-search').addEventListener('input',()=>{clearTimeout(suggestTimer);const q=$('sv-member-search').value.trim();if(!q)render();else if(q.length>=(/^[0-9]+$/.test(q)?3:2))suggestTimer=setTimeout(render,350);});
 document.addEventListener('sv-directory-render',render);
 document.addEventListener('sv-plan-options',e=>{plans=e.detail;});
 document.addEventListener('click',e=>{
  const member=e.target.closest('[data-member]');if(member){customer=members.find(c=>c.id===member.dataset.member);details();document.dispatchEvent(new CustomEvent('sv-member-selected',{detail:{...customer}}));location.hash=$('sv-account-summary')?'s13':'s04';}
  const detail=e.target.closest('[data-customer]');if(detail)customer=members.find(c=>c.id===detail.dataset.customer)||customer;
  if(e.target.closest('[data-open-sheet]'))open();
  const option=e.target.closest('[data-sv-plan]');if(option){
   chosen=plans.find(p=>p.id===option.dataset.svPlan);
   document.querySelectorAll('[data-sv-plan]').forEach(x=>x.setAttribute('aria-checked',String(x===option)));
   $('sheet-hint').textContent='确认表示已线下收取 '+money(chosen.paid);
   $('confirm-recharge').textContent='确认储值 · '+money(chosen.paid);$('confirm-recharge').disabled=false;
  }
 });
 $('close-sheet').addEventListener('click',close);
 $('recharge-overlay').addEventListener('click',e=>{if(e.target===$('recharge-overlay'))close();});
 document.addEventListener('keydown',e=>{if(e.key==='Escape')close();});
 $('confirm-recharge').addEventListener('click',()=>{
  if(status==='CREDITED'){close();return;}if(!chosen||status==='PROCESSING')return;
  status='PROCESSING';$('confirm-recharge').disabled=true;$('close-sheet').disabled=true;$('sheet-options').hidden=true;
  $('sheet-title').textContent='储值处理中';$('sheet-hint').textContent='正在查询到账结果';
  setTimeout(()=>{
   customer.balance=(customer.balance??0)+chosen.paid+chosen.bonus;
   // Deterministic virtual time, increasing with each demo command.
   customer.last='2026-10-20 16:'+String(++tick).padStart(2,'0');status='CREDITED';render();details();
   document.dispatchEvent(new CustomEvent('sv-prototype-credited',{detail:{...customer,plan:{...chosen}}}));
   $('sheet-title').textContent='储值成功';$('sheet-hint').textContent='本次到账 '+money(chosen.paid+chosen.bonus);
   $('confirm-recharge').textContent='完成';$('confirm-recharge').disabled=false;$('close-sheet').disabled=false;
  },600);
 });
 window.addEventListener('hashchange',()=>{close();details();});render();details();
})();
