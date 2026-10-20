/* Prototype only. No real APIs, collections or persistent customer data. */
(() => {
 const $=id=>document.getElementById(id);
 const state={name:'林女士',phone:'13800000001',balance:0,credited:false,plan:null,lastTotal:0,status:'IDLE',timer:null};
 const money=n=>`¥${n.toFixed(2)}`;
 const total=()=>state.plan+({50:10,100:30,200:50})[state.plan];
 const text=(key,value)=>document.querySelectorAll(`[data-${key}]`).forEach(el=>el.textContent=value);
 function render(){
  text('customer-name',state.name);text('customer-phone',state.phone.slice(0,3)+'****'+state.phone.slice(-4));
  text('balance',state.credited?money(state.balance):'未储值');text('balance-label',state.credited?`本店余额 ${money(state.balance)}`:'本店尚未储值');
  text('record',state.credited?`最近储值 +${money(state.lastTotal)} · 今天 14:30`:'暂无储值记录');
  $('account-created').hidden=!state.credited;$('account-empty').hidden=state.credited;
 }
 function openSheet(){
  if(state.status==='PROCESSING')return;
  state.plan=null;state.status='IDLE';
  document.querySelector('figure.is-active .ph').append($('recharge-overlay'));
  $('recharge-overlay').hidden=false;$('sheet-options').hidden=false;$('sheet-title').textContent='选择储值方案';
  document.querySelectorAll('[data-plan]').forEach(el=>el.setAttribute('aria-checked','false'));
  $('sheet-hint').textContent='请选择储值方案';$('confirm-recharge').textContent='确认储值';$('confirm-recharge').disabled=true;
  render();document.querySelector('[data-plan]').focus();
 }
 function closeSheet(){if(state.status==='PROCESSING')return;$('recharge-overlay').hidden=true;}
 function show(){
  const id=location.hash.slice(1)||'s03';
  if(!document.getElementById(id)?.matches('figure')){location.hash='s04';return;}
  $('recharge-overlay').hidden=true;
  document.querySelectorAll('figure').forEach(el=>el.classList.toggle('is-active',el.id===id));
  document.querySelectorAll('.review-links a').forEach(a=>a.setAttribute('aria-current',a.hash===`#${id}`?'page':'false'));
  render();
 }
 $('member-search-form').addEventListener('submit',e=>{
  e.preventDefault();const phone=$('member-search').value.trim();
  if(!/^1[3-9]\d{9}$/.test(phone)){$('search-hint').textContent='请输入完整手机号';return;}
  const found=phone===state.phone;$('member-match').hidden=!found;$('member-not-found').hidden=found;
  $('search-hint').textContent=found?'':'可新增会员后继续储值';$('new-phone').value=phone;
 });
 document.querySelectorAll('[data-open-sheet]').forEach(el=>el.addEventListener('click',openSheet));
 $('scan-demo').addEventListener('click',openSheet);
 $('close-sheet').addEventListener('click',closeSheet);
 $('recharge-overlay').addEventListener('click',e=>{if(e.target===$('recharge-overlay'))closeSheet();});
 document.addEventListener('keydown',e=>{if(e.key==='Escape')closeSheet();});
 document.querySelectorAll('[data-plan]').forEach(el=>el.addEventListener('click',()=>{
  state.plan=Number(el.dataset.plan);
  document.querySelectorAll('[data-plan]').forEach(x=>x.setAttribute('aria-checked',String(x===el)));
  $('confirm-recharge').disabled=false;$('confirm-recharge').textContent=`确认储值 · ${money(state.plan)}`;
  $('sheet-hint').textContent=`确认表示已线下收取 ${money(state.plan)}`;
 }));
 $('confirm-recharge').addEventListener('click',()=>{
  if(state.status==='CREDITED'){closeSheet();return;}
  if(!state.plan||state.status==='PROCESSING')return;
  state.status='PROCESSING';$('confirm-recharge').disabled=true;$('close-sheet').disabled=true;
  $('sheet-options').hidden=true;$('sheet-title').textContent='储值处理中';$('sheet-hint').textContent='已确认收款，正在查询到账结果…';$('confirm-recharge').textContent='处理中';
  state.timer=setTimeout(()=>{
   state.lastTotal=total();state.balance+=state.lastTotal;state.credited=true;state.status='CREDITED';render();
   $('sheet-title').textContent='储值成功';$('sheet-hint').textContent=`本次到账 ${money(total())}，本店余额 ${money(state.balance)}`;
   $('confirm-recharge').textContent='完成';$('confirm-recharge').disabled=false;$('close-sheet').disabled=false;
  },900);
 });
 $('member-add-form').addEventListener('submit',e=>{
  e.preventDefault();const phone=$('new-phone').value.trim();
  if(!/^1[3-9]\d{9}$/.test(phone)){$('add-hint').textContent='请输入正确的手机号';return;}
  if(phone===state.phone){$('add-hint').textContent='该会员已存在，请返回选用';return;}
  Object.assign(state,{name:$('new-name').value.trim()||'新会员',phone,balance:0,credited:false});$('add-hint').textContent='';
  const next=e.submitter?.dataset.next;
  location.hash=next==='sheet'?'s04':'s02';
  if(next==='sheet')window.addEventListener('hashchange',openSheet,{once:true});
 });
 $('account-search').addEventListener('input',()=>{
  const q=$('account-search').value.trim(),found=state.credited&&(!q||state.name.includes(q)||state.phone.endsWith(q));
  $('account-created').hidden=!found;$('account-empty').hidden=found;
 });
 window.addEventListener('hashchange',show);show();
})();
