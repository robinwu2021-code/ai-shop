(() => {
  const kinds = {
    ORDER:{title:'订单优惠',types:[['满额立减','满200减30'],['满件优惠','满3件减20'],['指定商品特价','指定商品限时特价']]},
    COUPON:{title:'卡券活动',types:[['进店领券','顾客领取已发布护理体验券'],['定向发券','向指定会员人群发放'],['生日礼券','按生日人群发放已定义权益']]},
    RECHARGE:{title:'储值活动',types:[['固定档位充赠','充50送10 / 充100送30 / 充200送50'],['普通储值规则','实收即入账，不赠送'],['美业储值充赠','护理项目储值优惠']]},
    GIFT:{title:'赠礼与积分',types:[['满额赠品','满300赠护理小样'],['买赠','买2赠1'],['消费赠积分','按积分域发行规则与预算执行']]},
    GROUP:{title:'拼团与集单',types:[['多人成团','2人成团，每人99元'],['每日社区集单','每日20:00截单，20份起订']]}
  };
  const state = {category:'RECHARGE', type:'固定档位充赠', custom:false, name:'秋日储值 · 充1000赠200', rule:'实收1000元，额外赠送200元', paused:false, context:'RECHARGE'};
  const $ = s => document.querySelector(s);
  const esc = s => String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const ruleRow = (k,v) => '<div class="frow"><span class="k">'+k+'</span><span class="v">'+v+'</span></div>';
  const input = (id,label,value,type='number') => '<label class="review-field">'+label+'<input id="'+id+'" class="inp" type="'+type+'" value="'+value+'" min="0" step="0.01" required></label>';
  const defaultTiers=()=>[{paid:'50',bonus:'10'},{paid:'100',bonus:'30'},{paid:'200',bonus:'50'}];
  state.tiers=defaultTiers();
  state.publishedRecharge=null;
  const moneyText=v=>(Number(v)/100).toFixed(2).replace(/\.00$/,'');
  function minor(v){
    if(!/^\d+(\.\d{1,2})?$/.test(v))return null;
    const [a,b='']=v.split('.');const n=Number(a)*100+Number(b.padEnd(2,'0'));
    return Number.isSafeInteger(n)?n:null;
  }
  function tierTotal(t){const p=minor(t.paid),b=minor(t.bonus);return p!==null&&b!==null&&Number.isSafeInteger(p+b)?moneyText(p+b)+'元':'请填写有效金额';}
  function renderTiers(){
    $('#recharge-tiers').innerHTML=state.tiers.map((t,i)=>'<div class="card"><div class="hd"><b>档位'+(i+1)+'</b>'+(state.tiers.length>1?'<button class="btn sec sm proto-control" type="button" data-remove-tier="'+i+'">删除</button>':'')+'</div><label class="review-field">实收金额（元）<input class="inp" type="number" min="0.01" step="0.01" data-tier="'+i+'" data-field="paid" value="'+esc(t.paid)+'"></label><label class="review-field">赠送金额（元）<input class="inp" type="number" min="0" step="0.01" data-tier="'+i+'" data-field="bonus" value="'+esc(t.bonus)+'" '+(state.type==='普通储值规则'?'readonly':'')+'></label><div class="meta">到账合计：<b data-tier-total="'+i+'">'+tierTotal(t)+'</b></div></div>').join('')+'<button type="button" class="btn sec proto-control" data-add-tier '+(state.tiers.length>=50?'disabled':'')+'>＋ 增加档位</button><div class="meta">每次选择一个固定档位，不叠加、不按金额区间自动取档。</div>';
  }
  function validTiers(){
    const seen=new Set();
    for(const t of state.tiers){
      const p=minor(t.paid),b=minor(t.bonus);
      if(p===null||p<=0||b===null||!Number.isSafeInteger(p+b))return '请填写有效实收和赠送金额，最多两位小数';
      if(seen.has(p))return '同一活动的实收金额档位不能重复';
      if(state.type==='普通储值规则'&&b!==0)return '普通储值规则的赠送额必须为0';
      seen.add(p);
    }
    return state.tiers.length&&state.tiers.length<=50?'':'至少配置一个档位，最多50个';
  }
  const tiersSummary=tiers=>tiers.map(t=>'充'+t.paid+'元送'+t.bonus+'元，到账'+tierTotal(t)).join('；');
  const tiersRows=tiers=>'<div class="group">'+tiers.map(t=>ruleRow('充'+esc(t.paid)+'送'+esc(t.bonus),'到账'+tierTotal(t))).join('')+'</div>';
  function renderPresets(){
    $('[data-category-title]').textContent=kinds[state.category].title;
    $('#preset-list').innerHTML=kinds[state.category].types.map(([t,d])=>'<a class="card" href="#s05" data-type="'+esc(t)+'"><div class="hd"><b>'+t+'</b><span>›</span></div><div class="meta">'+d+'</div></a>').join('');
  }
  function renderForm(){
    document.querySelectorAll('[data-type-title]').forEach(x=>x.textContent=state.type);
    const refs = {
      RECHARGE: '<a class="card" href="#s12"><b>基础充值方案：日常储值 · 版本1 ›</b><span class="meta">基础金额与使用范围形成快照；活动只加赠送规则。</span></a><div class="gt">固定充赠档位 · 每次选择一档</div><div id="recharge-tiers"></div>',
      ORDER: '<div class="group">'+input('rule-a',state.type==='满件优惠'?'订单件数':state.type==='指定商品特价'?'原商品价格（元）':'订单满额（元）',state.custom?'':state.type==='满件优惠'?'3':'200')+input('rule-b',state.type==='指定商品特价'?'活动价格（元）':'减免金额（元）',state.custom?'':'30')+(state.type==='指定商品特价'?ruleRow('适用商品','已选基础护理服务'):ruleRow('适用范围','当前活动参与商品'))+'</div>',
      COUPON:'<a class="card" href="#s09"><b>选择已发布券模板：护理体验券 · 版本1 ›</b><span class="meta">券内容：基础护理1次 · 领后30天；活动不重复定义。</span></a><div class="group">'+ruleRow('发放条件',state.type==='进店领券'?'顾客主动领取':'命中指定会员人群')+ruleRow('人群','近90天未到店会员')+ruleRow('数量上限','30张 · 每人1张')+'</div>',
      GIFT:'<div class="group">'+input('rule-a',state.type==='买赠'?'购买件数':'消费满额（元）',state.custom?'':state.type==='买赠'?'2':'300')+(state.type==='买赠'?input('rule-b','赠送件数','1'):state.type==='消费赠积分'?input('rule-b','赠送积分','30')+ruleRow('预算来源','积分域已核准的发行预算'):ruleRow('赠品','护理小样1份'))+ruleRow('履约',state.type==='消费赠积分'?'按积分域规则入账':'同订单一并履约')+'</div>',
      GROUP:'<div class="group">'+input('rule-a',state.type==='每日社区集单'?'起订份数':'成团人数',state.type==='每日社区集单'?'20':'2')+input('rule-b','每份价格（元）',state.custom?'':'99')+ruleRow('排期',state.type==='每日社区集单'?'每日20:00截单，次日配送':'开团后24小时内成团')+'</div>'
    };
    // Unimplemented form variations are shown as readable examples, never a real execution engine.
    $('#rule-form').innerHTML=refs[state.category]+'<div class="note">原型规则示例；实际可配置项按所选类型的合法字段展示。</div>';
    if(state.category==='RECHARGE')renderTiers();
  }
  function showDetail(){
    const created=state.context==='CREATED';
    const c=created?(state.category==='GROUP'&&state.type==='每日社区集单'?'PERIOD':state.category):state.context;
    let name=state.name,rule=state.rule,links='';
    if(c==='GROUP'){name='双人护理拼团';rule='2人成团，每人99元；开团后24小时截止';links='<a class="card" href="#s15"><b>查看该活动开的团 ›</b><span class="meta">团G001待成团，团G002已成团</span></a>';}
    else if(c==='PERIOD'){name='鲜果每日集单';rule='每日20:00截单，20份起订，次日履约';links='<a class="card" href="#s17"><b>查看该活动的集单期 ›</b><span class="meta">10月20日期与10月19日期</span></a>';}
    else if(c==='ORDER'){name='会员满200减30';rule='订单满200元减30元，活动间按既定取优规则';}
    else if(c==='COUPON'){name=state.category==='COUPON'?state.name:'老客护理唤醒礼';rule='命中人群发放护理体验券，最多30张，每人1张';links='<a class="card" href="#s09"><b>引用的券模板：护理体验券 · 版本1 ›</b></a><a class="card" href="#s10"><b>查看本活动发放批次 ›</b></a>';}
    else {links='<a class="card" href="#s12"><b>引用基础规则：日常储值 · 版本1 ›</b></a><a class="card" href="#s13"><b>查看活动入账来源与账户流水 ›</b></a>';}
    if(created){name=state.name;rule=state.rule;}
    else if(c==='RECHARGE'){name='秋日储值 · 充1000赠200';rule='实收1000元，额外赠送200元';}
    if(c==='GIFT')links='<a class="card" href="#s19"><b>赠品或积分发放记录 ›</b></a>';
    $('#s07 .scr').innerHTML='<div class="card"><div class="hd"><b>'+esc(name)+'</b><span class="chip '+(state.paused?'':'ok')+'">'+(state.paused?'已暂停':'进行中')+'</span></div><div class="meta">活动规则版本1 · 全部门店</div></div><div class="card"><b>活动规则</b><div>'+esc(rule)+'</div></div>'+links+'<a class="card" href="#s19"><b>查看活动效果 ›</b></a><div class="note">暂停只停止新增参与，已获得权益及已产生的团/期继续按原规则处理。</div>';
    $('#s21 [data-activity-name]').textContent=name;
  }
  document.addEventListener('input',e=>{const x=e.target.closest('[data-tier]');if(!x)return;const i=Number(x.dataset.tier);state.tiers[i][x.dataset.field]=x.value;$('[data-tier-total="'+i+'"]').textContent=tierTotal(state.tiers[i]);});
  function navigate(id){if(id==='s14')id='s25';
    if(!document.getElementById(id))id='s01';
    document.querySelectorAll('figure').forEach(x=>x.classList.toggle('is-active',x.id===id));
    document.querySelectorAll('.review-links a').forEach(x=>x.getAttribute('href')==='#'+id?x.setAttribute('aria-current','page'):x.removeAttribute('aria-current'));
    if(id==='s04')renderPresets();
    if(id==='s05')renderForm();
    if(id==='s07')showDetail();
    history.replaceState(null,'','#'+id);
    const scr=$('#'+id+' .scr'); if(scr)scr.scrollTop=0;
    if(window.matchMedia('(max-width:800px)').matches)document.getElementById(id).scrollIntoView({block:'start'});
  }
  document.addEventListener('click',e=>{
    if(e.target.closest('[data-add-tier]')){if(state.tiers.length<50)state.tiers.push({paid:'',bonus:state.type==='普通储值规则'?'0':''});renderTiers();return;}
    const remove=e.target.closest('[data-remove-tier]');if(remove){if(state.tiers.length>1)state.tiers.splice(Number(remove.dataset.removeTier),1);renderTiers();return;}
    const cat=e.target.closest('[data-category]');if(cat){state.category=cat.dataset.category;state.type=kinds[state.category].types[0][0];state.custom=false;}
    const type=e.target.closest('[data-type]');if(type){state.type=type.dataset.type;state.custom=false;}
    if(e.target.closest('[data-custom]')){state.custom=true;state.type='自定义'+kinds[state.category].title;}
    if(cat||type||e.target.closest('[data-custom]'))state.tiers=state.custom?[{paid:'',bonus:''}]:state.type==='普通储值规则'?[{paid:'50',bonus:'0'},{paid:'100',bonus:'0'},{paid:'200',bonus:'0'}]:defaultTiers();
    const demo=e.target.closest('[data-demo]');
    if(demo){
      if(demo.closest('#s21'))state.paused=true;
      let feedback=demo.closest('figure').querySelector('[data-feedback]');
      if(!feedback){feedback=document.createElement('div');feedback.className='note';feedback.dataset.feedback='';feedback.setAttribute('role','status');demo.closest('figure').querySelector('.scr').append(feedback);}
      feedback.textContent=demo.dataset.demo;feedback.scrollIntoView({block:'nearest'});return;
    }
    const link=e.target.closest('a[href^="#"]');if(!link)return;
    e.preventDefault();
    if(link.hash==='#s07'){
      const origin=link.closest('figure')?.id;
      state.context=['s15','s16'].includes(origin)?'GROUP':['s17','s18'].includes(origin)?'PERIOD':origin==='s02'&&link.textContent.includes('满200')?'ORDER':'RECHARGE';
    }
    navigate(link.hash.slice(1));
  });
  $('#activity-form').addEventListener('submit',e=>e.preventDefault());
  $('#review-activity').addEventListener('click',()=>{
    const name=$('#activity-name').value.trim();
    const a=$('#rule-a'),b=$('#rule-b');
    let error='';
    if(!name)error='请填写本次活动的名称';
    else if(state.category==='RECHARGE')error=validTiers();
    else if([a,b].filter(Boolean).some(x=>!x.value || !/^\d+(\.\d{1,2})?$/.test(x.value)))error='请填写有效金额/数量，最多两位小数';
    else if(a && Number(a.value)<=0)error='参与金额或数量需大于0';
    else if(state.category==='RECHARGE' && state.type==='普通储值规则' && b && Number(b.value)!==0)error='普通储值不含活动赠送；有赠送请选固定档位充赠';
    else if(state.category==='ORDER' && state.type!=='满件优惠' && b && Number(b.value)>Number(a.value))error='优惠金额或活动价格不能高于订单门槛或原价';
    else if((state.type==='满件优惠'||state.type==='买赠'||state.category==='GROUP') && a && !Number.isInteger(Number(a.value)))error='件数、人数或起订量需要为整数';
    $('#form-error').textContent=error;$('#form-error').hidden=!error;
    if(error)return;
    state.name=name;state.paused=false;
    state.rule=state.category==='RECHARGE'?tiersSummary(state.tiers):state.category==='ORDER'?'订单满'+a.value+'元减'+b.value+'元':state.category==='COUPON'?'引用护理体验券版本1，最多30张，每人1张':state.category==='GIFT'?'消费满'+a.value+'元赠护理小样1份':state.type+'，数量门槛'+a.value+'，每份'+b.value+'元';
    if(state.category==='ORDER'&&state.type==='满件优惠')state.rule='订单满'+a.value+'件减'+b.value+'元';
    if(state.category==='ORDER'&&state.type==='指定商品特价')state.rule='指定服务活动价'+b.value+'元，原价'+a.value+'元';
    if(state.category==='GIFT'&&state.type==='买赠')state.rule='指定商品买'+a.value+'件赠'+b.value+'件';
    if(state.category==='GIFT'&&state.type==='消费赠积分')state.rule='消费满'+a.value+'元赠'+b.value+'积分，按积分预算执行';
    document.querySelectorAll('[data-activity-name]').forEach(x=>x.textContent=state.name);
    $('#review-rules').textContent=state.rule;
    if(state.category==='RECHARGE')$('#review-rules').innerHTML=tiersRows(state.tiers);
    navigate('s06');
  });
  $('#publish-demo').addEventListener('click',()=>{state.context='CREATED';if(state.category==='RECHARGE'){state.publishedRecharge={name:state.name,tiers:state.tiers.map(t=>({...t}))};}showDetail();navigate('s07');});
  window.addEventListener('hashchange',()=>navigate(location.hash.slice(1)));
  renderPresets();renderForm();navigate(location.hash.slice(1)||'s01');
})();
