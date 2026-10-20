/* Design-only interaction: no fetch, real accounts or persisted personal data. */
(() => {
 const $ = id => document.getElementById(id);
 const state = { name:'林女士', phone:'13800000001', paid:100, bonus:30, balance:0, credited:false, method:'现金', receipt:'', status:'DRAFT' };
 const money = n => `¥${n.toFixed(2)}`;
 const text = (key,value) => document.querySelectorAll(`[data-${key}]`).forEach(el => { el.textContent=value; });
 function render(){
  text('customer-name',state.name);text('customer-phone',state.phone.slice(0,3)+'****'+state.phone.slice(-4));
  text('customer-state','会员');text('current-balance',state.credited?money(state.balance):'未开通储值');
  text('account-state',state.credited?'已有储值账户':'首次储值');text('paid',money(state.paid));text('bonus',money(state.bonus));
  text('total',money(state.paid+state.bonus));text('plan-name',`充${state.paid}送${state.bonus}`);
  text('final-balance',money(state.credited?state.balance:state.paid+state.bonus));text('payment',state.method);text('receipt',state.receipt||'—');
  $('account-created').hidden=!state.credited;$('account-empty').hidden=state.credited;
 }
 function show(){
  const id=location.hash.slice(1)||'s01';
  document.querySelectorAll('figure').forEach(el=>el.classList.toggle('is-active',el.id===id));
  if(!document.getElementById(id)?.matches('figure')){location.hash='s01';return;}
  document.querySelectorAll('.review-links a').forEach(a=>{a.setAttribute('aria-current',a.hash===`#${id}`?'page':'false');});
  render();
 }
 $('member-search-form').addEventListener('submit',e=>{
  e.preventDefault();const phone=$('member-search').value.trim();
  if(!/^1[3-9]\d{9}$/.test(phone)){ $('search-hint').textContent='请输入完整手机号';return; }
  const found=phone==='13800000001';$('member-match').hidden=!found;$('member-not-found').hidden=found;
  $('search-hint').textContent=found?'':'可新增会员后继续储值';$('new-phone').value=phone;
 });
 document.querySelectorAll('[data-choose]').forEach(el=>el.addEventListener('click',()=>render()));
 $('scan-demo').addEventListener('click',()=>{ $('search-hint').textContent='已识别演示会员码';location.hash='s04'; });
 $('member-add-form').addEventListener('submit',e=>{
  e.preventDefault();const phone=$('new-phone').value.trim();
  if(!/^1[3-9]\d{9}$/.test(phone)){ $('add-hint').textContent='请输入正确的手机号';return; }
  if(phone===state.phone){ $('add-hint').textContent='该手机号已存在，请选择已有会员';return; }
  Object.assign(state,{name:$('new-name').value.trim()||'新会员',phone,balance:0,credited:false,status:'DRAFT'});
  $('add-hint').textContent='';location.hash=e.submitter?.dataset.next||'s06';render();
 });
 $('plan-picker').addEventListener('change',()=>{state.paid=Number($('plan-picker').value);state.bonus=({50:10,100:30,200:50})[state.paid];render();});
 $('review-recharge').addEventListener('click',()=>{
  const receipt=$('receipt-no').value.trim();if(!receipt){$('recharge-hint').textContent='请填写收款凭据';return;}
  state.receipt=receipt;state.method=$('payment-method').value;$('recharge-hint').textContent='';location.hash='s07';
 });
 $('continue-recharge').addEventListener('click',()=>{state.status='DRAFT';state.receipt='';$('receipt-no').value='';render();});
 $('confirm-recharge').addEventListener('click',()=>{state.status='RECEIVED';location.hash='s08';});
 $('receiver-demo').addEventListener('click',()=>{state.status='RECEIVED';location.hash='s08';});
 $('refresh-progress').addEventListener('click',()=>{
  if(state.status==='RECEIVED'){state.balance+=state.paid+state.bonus;state.credited=true;state.status='CREDITED';}
  if(state.status==='CREDITED')location.hash='s09';
  else $('progress-copy').textContent='请先在确认页完成演示收款确认。';render();
 });
 $('cancel-demo').addEventListener('click',()=>{state.status='CANCELLED';$('pending-copy').textContent='已取消，未增加余额。';$('receiver-demo').disabled=true;$('cancel-demo').disabled=true;});
 $('account-search').addEventListener('input',()=>{
  const q=$('account-search').value.trim(),match=state.credited&&(!q||state.name.includes(q)||state.phone.endsWith(q));
  $('account-created').hidden=!match;$('account-empty').hidden=match;$('account-empty').textContent=state.credited?'未找到储值账户':'暂无储值账户';
 });
 document.querySelectorAll('[data-filter]').forEach(el=>el.addEventListener('click',()=>{
  document.querySelectorAll('[data-filter]').forEach(x=>x.setAttribute('aria-selected',String(x===el)));
  const empty=el.dataset.filter==='REDEMPTION';$('recharge-record').hidden=empty;$('record-empty').hidden=!empty;
 }));
 window.addEventListener('hashchange',show);show();
})();
