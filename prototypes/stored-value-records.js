(() => {
  const $ = s => document.querySelector(s);
  const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const customers = [
    {id:'chen',name:'陈女士',phone:'138****0628',search:'0628',balance:10000},
    {id:'lin',name:'林女士',phone:'139****1216',search:'1216',balance:31000},
    {id:'zhou',name:'周先生',phone:'137****0918',search:'0918',balance:0}
  ];
  const records = [
    {id:'S003',customer:'lin',kind:'redeem',time:'2026-10-20 15:30',store:'万达店',name:'游泳单次',amount:-2000,balance:31000},
    {id:'S001',customer:'chen',kind:'redeem',time:'2026-10-20 14:20',store:'人民路店',name:'基础护理',amount:-3000,balance:10000},
    {id:'R001',customer:'chen',kind:'recharge',time:'2026-10-20 10:05',store:'人民路店',name:'充100送30',amount:13000,balance:13000,paid:10000,bonus:3000,plan:'plan100'},
    {id:'R003',customer:'lin',kind:'recharge',time:'2026-10-19 11:00',store:'万达店',name:'充300送30',amount:33000,balance:33000,paid:30000,bonus:3000},
    {id:'S002',customer:'zhou',kind:'redeem',time:'2026-10-18 16:40',store:'人民路店',name:'肩颈护理',amount:-6000,balance:0},
    {id:'R002',customer:'zhou',kind:'recharge',time:'2026-10-17 09:30',store:'人民路店',name:'充50送10',amount:6000,balance:6000,paid:5000,bonus:1000,plan:'plan50'}
  ].sort((a,b)=>b.time.localeCompare(a.time)||b.id.localeCompare(a.id));
  let selected='chen',scope=null,recordId='S001',origin='s26',type='all',start='',end='';
  const money=n=>'¥'+(Math.abs(n)/100).toFixed(2);
  const owner=r=>customers.find(c=>c.id===r.customer);
  const row=(k,v)=>'<div class="frow"><span class="k">'+k+'</span><span class="v">'+v+'</span></div>';
  function recordLink(r,showCustomer=true){return '<a href="#s27" class="sv-record" data-record="'+r.id+'"><div class="hd"><b>'+esc(r.kind==='recharge'?'储值 · '+r.name:'核销 · '+r.name)+'</b><strong class="num">'+(r.amount>0?'+':'−')+money(r.amount)+'</strong></div><div class="meta">'+(showCustomer?esc(owner(r).name)+' · ':'')+esc(r.store)+'</div><div class="meta sv-split"><span>'+r.time+'</span><span class="num">余额 '+money(r.balance)+'</span></div></a>';}
  function accountList(){
    const q=$('#sv-account-search').value.trim().toLowerCase();
    const list=customers.map(c=>({...c,last:records.find(r=>r.customer===c.id)?.time||''})).sort((a,b)=>b.last.localeCompare(a.last)||a.id.localeCompare(b.id)).filter(c=>(c.name+c.phone+c.search).toLowerCase().includes(q));
    $('#sv-account-list').innerHTML=list.map(c=>'<a class="card" href="#s13" data-customer="'+c.id+'"><div class="hd"><b>'+esc(c.name)+'</b><span class="sv-link">详情 ›</span></div><div class="meta">'+esc(c.phone)+'</div><div class="sv-split"><span class="meta">可用余额</span><strong class="num sv-balance">'+money(c.balance)+'</strong></div><div class="meta">最近收支 '+c.last+'</div></a>').join('')||'<div class="card sv-empty">未找到顾客</div>';
  }
  function accountDetail(){const c=customers.find(x=>x.id===selected);$('#sv-account-summary').innerHTML='<div class="card"><b>'+esc(c.name)+'</b><span class="meta">'+esc(c.phone)+'</span><span class="meta">可用余额</span><strong class="num sv-balance">'+money(c.balance)+'</strong></div>';$('#sv-account-recent').innerHTML=records.filter(r=>r.customer===selected).slice(0,3).map(r=>recordLink(r,false)).join('');}
  function recordList(){
    $('#s26 .bk').href=scope?'#s13':'#s11';
    $('#sv-record-context').hidden=!scope;
    $('#sv-record-context').textContent=scope?customers.find(c=>c.id===scope).name:'';
    document.querySelectorAll('[data-record-type]').forEach(x=>x.setAttribute('aria-selected',String(x.dataset.recordType===type)));
    $('#sv-open-time').textContent=start||end?(start&&end?start.slice(5).replace('-','/')+'–'+end.slice(5).replace('-','/'):start?start.slice(5).replace('-','/')+'起':end.slice(5).replace('-','/')+'前')+' ▾':'时间 ▾';
    $('#sv-open-time').classList.toggle('is-active',Boolean(start||end));
    $('#sv-open-time').setAttribute('aria-label',start||end?'时间筛选：'+(start||'不限')+' 至 '+(end||'不限'):'时间筛选');
    const list=records.filter(r=>(!scope||r.customer===scope)&&(type==='all'||r.kind===type)&&(!start||r.time.slice(0,10)>=start)&&(!end||r.time.slice(0,10)<=end));
    let date='';$('#sv-record-list').innerHTML=list.map(r=>{const d=r.time.slice(0,10);const heading=d!==date?'<div class="sv-date">'+d+'</div>':'';date=d;return heading+'<div class="card">'+recordLink(r)+'</div>';}).join('')||'<div class="card sv-empty">暂无符合条件的记录</div>';
  }
  function recordDetail(){const r=records.find(x=>x.id===recordId);$('#s27 .bk').href='#'+origin;$('#sv-record-detail').innerHTML='<div class="card"><div class="hd"><b>'+(r.kind==='recharge'?'储值到账':'核销扣款')+'</b><span class="chip ok">已完成</span></div><strong class="num sv-balance">'+(r.amount>0?'+':'−')+money(r.amount)+'</strong><span>'+esc(r.name)+'</span></div><div class="group">'+row('顾客',esc(owner(r).name))+row('门店',esc(r.store))+row('发生时间',r.time)+row('单号',r.id)+row('变动后余额','<span class="num">'+money(r.balance)+'</span>')+'</div>'+(r.kind==='recharge'?'<div class="group">'+row('实收金额','<span class="num">'+money(r.paid)+'</span>')+row('赠送金额','<span class="num">'+money(r.bonus)+'</span>')+row('储值方案',esc(r.name)+' · v1')+'</div>':'');}
  function render(id){if(id==='s25')accountList();if(id==='s13')accountDetail();if(id==='s26')recordList();if(id==='s27')recordDetail();if(id==='s14')document.querySelectorAll('[data-sv-charge-customer]').forEach(x=>x.textContent=customers.find(c=>c.id===selected).name);}
  document.addEventListener('click',e=>{
    const c=e.target.closest('[data-customer]');if(c)selected=c.dataset.customer;
    const rec=e.target.closest('[data-record]');if(rec){recordId=rec.dataset.record;origin=rec.closest('figure').id;}
    const link=e.target.closest('a[href^="#"]');if(link){if(link.hash==='#s26'){scope=link.hasAttribute('data-account-records')?selected:null;}render(link.hash.slice(1));}
    const tab=e.target.closest('[data-record-type]');if(tab){type=tab.dataset.recordType;recordList();}
    if(e.target.closest('#sv-open-time'))timeDialog(true);
    if(e.target.closest('#sv-cancel-time')||e.target.id==='sv-time-overlay')timeDialog(false);
    if(e.target.closest('#sv-clear-time')){start='';end='';recordList();timeDialog(false);}
    if(e.target.closest('#sv-apply-time')){
      const a=$('#sv-record-start').value,b=$('#sv-record-end').value;
      const invalid=a&&b&&a>b;$('#sv-record-error').hidden=!invalid;$('#sv-record-error').textContent=invalid?'结束日期不能早于开始日期':'';
      if(!invalid){start=a;end=b;recordList();timeDialog(false);}
    }
  });
  document.addEventListener('input',e=>{if(e.target.id==='sv-account-search')accountList();});
  function timeDialog(open){
    $('#sv-time-overlay').hidden=!open;
    $('#s26 .scr').inert=open;$('#s26 .nav').inert=open;
    if(open){$('#sv-record-start').value=start;$('#sv-record-end').value=end;$('#sv-record-error').hidden=true;$('#sv-cancel-time').focus();}
    else $('#sv-open-time').focus();
  }
  document.addEventListener('keydown',e=>{
    if($('#sv-time-overlay').hidden)return;
    if(e.key==='Escape'){e.preventDefault();timeDialog(false);}
    if(e.key==='Tab'){
      const controls=Array.from($('#sv-time-overlay').querySelectorAll('button,input'));const first=controls[0],last=controls[controls.length-1];
      if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}
      else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}
    }
  });
  window.addEventListener('hashchange',()=>render(location.hash.slice(1)));
  render(location.hash.slice(1));
})();
