(() => {
  const plans=[{id:'plan50',name:'充50送10',paid:'50',bonus:'10',enabled:true},{id:'plan100',name:'充100送30',paid:'100',bonus:'30',enabled:true},{id:'plan200',name:'充200送50',paid:'200',bonus:'50',enabled:true}];
  let selected='plan100',pending=null;
  const $=s=>document.querySelector(s);
  const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const row=(k,v)=>'<div class="frow"><span class="k">'+k+'</span><span class="v">'+v+'</span></div>';
  function minor(v){if(!/^\d+(\.\d{1,2})?$/.test(v))return null;const [a,b='']=v.split('.');const n=Number(a)*100+Number(b.padEnd(2,'0'));return Number.isSafeInteger(n)?n:null;}
  function total(p){const a=minor(p.paid),b=minor(p.bonus);return a!==null&&b!==null&&Number.isSafeInteger(a+b)?((a+b)/100).toFixed(2).replace(/\.00$/,'')+'元':'填写有效金额后计算';}
  function summary(p){return '<div class="card"><div class="hd"><b>'+esc(p.name)+'</b><span class="chip '+(p.enabled?'ok':'')+'">'+(p.enabled?'已启用':'已停用')+'</span></div><span class="meta">版本1 · 当前门店</span></div><div class="group">'+row('实收金额',esc(p.paid)+'元')+row('赠送金额',esc(p.bonus)+'元')+row('到账合计',total(p))+row('适用门店','当前门店')+'</div>';}
  function list(){ const status=$('#sv-plan-status').value;const visible=plans.filter(p=>status==='all'||p.enabled===(status==='active'));$('#standalone-plan-list').innerHTML=visible.map(p=>'<a class="card" href="#s24" data-plan="'+p.id+'"><div class="hd"><b>'+esc(p.name)+'</b><span class="chip '+(p.enabled?'ok':'')+'">'+(p.enabled?'已启用':'已停用')+'</span></div><div class="sv-plan-amount"><span>到账 <strong class="num">'+total(p)+'</strong></span><span class="sv-link">详情 ›</span></div><div class="meta">实收'+esc(p.paid)+'元 · 赠送'+esc(p.bonus)+'元</div></a>').join('')||'<div class="card sv-empty">暂无储值方案</div>'; }
  function detail(){const p=plans.find(x=>x.id===selected)||plans[0];selected=p.id;$('#standalone-plan-detail').innerHTML=summary(p)+'<div class="sv-hint">停用后不再支持新办理，已有余额不受影响。</div><button class="sv-link" type="button" data-toggle-plan>'+(p.enabled?'停用方案':'启用方案')+'</button>';$('#use-standalone-plan').hidden=!p.enabled;}
  function charge(){
    const active=plans.filter(x=>x.enabled);
    if(!active.some(x=>x.id===selected))selected=active[0]?.id??'';
    const select=$('#standalone-charge-plan');select.innerHTML=active.map(p=>'<option value="'+p.id+'">'+esc(p.name)+' · 到账'+total(p)+'</option>').join('');select.value=selected;select.disabled=!active.length;
    $('#standalone-charge-summary').innerHTML=active.length?summary(active.find(x=>x.id===selected)):'<div class="note">暂无已启用的储值方案，请先创建或启用方案。</div>';
    $('#s14 .foot button').disabled=!active.length;
  }
  function render(id){if(id==='s11')list();if(id==='s24')detail();if(id==='s14')charge();}
  document.addEventListener('click',e=>{
    const chosen=e.target.closest('[data-plan]');if(chosen)selected=chosen.dataset.plan;
    if(e.target.closest('[data-toggle-plan]')){const p=plans.find(x=>x.id===selected);p.enabled=!p.enabled;detail();list();return;}
    const link=e.target.closest('a[href^="#"]');if(link)render(link.hash.slice(1));
  });
  document.addEventListener('change',e=>{if(e.target.id==='sv-plan-status')list();if(e.target.id==='standalone-charge-plan'){selected=e.target.value;charge();}});
  document.addEventListener('input',e=>{if(['plan-paid','plan-bonus'].includes(e.target.id))$('#plan-total').textContent=total({paid:$('#plan-paid').value,bonus:$('#plan-bonus').value});});
  $('#standalone-plan-form').addEventListener('submit',e=>e.preventDefault());
  $('#review-plan').addEventListener('click',()=>{
    const p={name:$('#plan-name').value.trim(),paid:$('#plan-paid').value,bonus:$('#plan-bonus').value,enabled:true};const a=minor(p.paid),b=minor(p.bonus);
    const error=!p.name?'请填写储值方案名称':a===null||a<=0||b===null||!Number.isSafeInteger(a+b)?'实收须大于0，赠送不小于0，最多两位小数':plans.some(x=>x.name===p.name)?'方案名称已存在，请使用可区分的名称':'';
    $('#plan-error').textContent=error;$('#plan-error').hidden=!error;if(error)return;
    pending=p;$('#standalone-plan-review').innerHTML=summary(p);location.hash='s23';
  });
  $('#create-plan-demo').addEventListener('click',()=>{if(!pending)return;const p={...pending,id:'demo-plan-'+(plans.length+1)};plans.push(p);selected=p.id;pending=null;list();location.hash='s24';});
  window.addEventListener('hashchange',()=>render(location.hash.slice(1)));
  list();render(location.hash.slice(1));
})();
