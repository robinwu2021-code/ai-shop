/* Local prototype interactions only; no network, payments or persistent business writes. */
const prices={fish:88,chicken:38,greens:28,rice:3,beef:58,shrimp:68,tofu:22,mushroom:26,soup:18,noodles:16,tea:12,juice:15};
const labels={fish:'松鼠桂鱼',chicken:'宫保鸡丁',greens:'上汤时蔬',rice:'米饭',beef:'黑椒牛柳',shrimp:'白灼虾',tofu:'家常豆腐',mushroom:'菌菇小炒',soup:'番茄蛋汤',noodles:'葱油拌面',tea:'桂花乌龙',juice:'鲜橙汁'};
let cart={fish:0,chicken:1,greens:1,rice:0,beef:0,shrimp:0,tofu:0,mushroom:0,soup:0,noodles:0,tea:0,juice:0},total=112,payMode='scan',timer,selectedFloorNo='A03',floorZone='hall',floorFilter='all';
function toast(message){const node=document.querySelector('#toast');node.textContent=message;node.hidden=false;clearTimeout(timer);timer=setTimeout(()=>node.hidden=true,3500)}
function moneyInput(value){if(!/^\d+(\.\d{1,2})?$/.test(value.trim()))return null;return Math.round(Number(value)*100)}
function renderCash(){const received=moneyInput(document.querySelector('#cash-received').value);document.querySelector('#change').textContent=received===null?'—':received<total*100?'实收不足':'¥'+((received-total*100)/100).toFixed(2)}
function render(){
 let amount=0,count=0;const list=document.querySelector('#cart-lines');list.replaceChildren();
 Object.keys(cart).forEach(k=>{
  if(!cart[k])return;count+=cart[k];amount+=cart[k]*prices[k];
  const row=document.createElement('div');row.className='cashier-row';
  const main=document.createElement('div');main.className='order-cart-line-main';
  const label=document.createElement('div');label.className='order-cart-item';
  const title=document.createElement('span');title.textContent=labels[k];label.append(title);
  if(k==='chicken'){
   const choice=document.createElement('select');choice.setAttribute('aria-label','宫保鸡丁口味');
   ['正常辣','少辣','不辣'].forEach(text=>{const option=document.createElement('option');option.value=text;option.textContent=text;choice.append(option)});
   choice.value=window.foodChickenFlavor||'正常辣';choice.onchange=()=>window.foodChickenFlavor=choice.value;label.append(choice);
  }
  const price=document.createElement('strong');price.textContent='¥'+prices[k]*cart[k];main.append(label,price);
  const adjust=document.createElement('div');adjust.className='order-cart-adjust';
  const minus=document.createElement('button');minus.dataset.sku=k;minus.dataset.delta='-1';minus.textContent='−';minus.setAttribute('aria-label','减少'+labels[k]);
  const qty=document.createElement('span');qty.dataset.count=k;qty.textContent=cart[k];qty.setAttribute('aria-label',labels[k]+'数量');
  const plus=document.createElement('button');plus.dataset.sku=k;plus.dataset.delta='1';plus.textContent='＋';plus.setAttribute('aria-label','增加'+labels[k]);
  const remove=document.createElement('button');remove.className='order-cart-remove';remove.dataset.removeSku=k;remove.textContent='移除';remove.setAttribute('aria-label','移除'+labels[k]);
  adjust.append(minus,qty,plus,remove);row.append(main,adjust);list.append(row);
 });
 if(!count){const p=document.createElement('p');p.className='cashier-muted';p.textContent='点击左侧菜品，直接加入购物车';list.append(p)}
 document.querySelector('#cart-count').textContent=count;const badge=document.querySelector('#pos-line-count');if(badge)badge.textContent=count;
 document.querySelector('#cart-amount').textContent='¥'+amount;document.querySelectorAll('[data-total]').forEach(e=>e.textContent='¥'+total);
 document.querySelector('#goods-amount').textContent='¥'+(total-12);renderCash();renderFloorSelection();
}
document.querySelector('#cart-lines').addEventListener('click',event=>{
 const button=event.target.closest('button');if(!button)return;
 if(button.dataset.removeSku){cart[button.dataset.removeSku]=0;render();return}
 if(button.dataset.sku&&button.dataset.delta){cart[button.dataset.sku]=Math.max(0,cart[button.dataset.sku]+Number(button.dataset.delta));render()}
});
document.querySelectorAll('[data-add-sku]').forEach(button=>button.onclick=()=>{cart[button.dataset.addSku]++;render()});
document.querySelector('#send').onclick=()=>{if(!Object.values(cart).some(Boolean))return toast('请先选择菜品');Object.keys(cart).forEach(k=>{total+=cart[k]*prices[k];cart[k]=0});render();toast('加菜送厨本地演示完成；尚未收款')};
document.querySelector('#search').oninput=e=>document.querySelectorAll('.cashier-dish').forEach(d=>d.hidden=!d.dataset.name.includes(e.target.value.trim()));
document.querySelectorAll('[data-state-filter]').forEach(b=>b.onclick=()=>{document.querySelectorAll('[data-state-filter]').forEach(x=>x.setAttribute('aria-pressed',x===b));document.querySelectorAll('[data-table]').forEach(t=>t.hidden=b.dataset.stateFilter!=='全部'&&t.dataset.state!==b.dataset.stateFilter&&(b.dataset.stateFilter!=='用餐中'||t.dataset.state!=='当前桌台'))});
document.querySelectorAll('[data-table]').forEach(b=>b.onclick=()=>{if(b.dataset.table==='A03')document.querySelector('#s02').scrollIntoView({behavior:'smooth'});else toast('本版只开放 A03 的完整操作演示')});
document.querySelectorAll('[data-pay]').forEach(b=>b.onclick=()=>{payMode=b.dataset.pay;document.querySelectorAll('[data-pay]').forEach(x=>x.setAttribute('aria-pressed',x===b));document.querySelector('#scan-panel').hidden=payMode!=='scan';document.querySelector('#cash-panel').hidden=payMode!=='cash';document.querySelector('#pay').textContent=payMode==='scan'?'生成付款入口':'登记现金收款';renderCash()});
document.querySelector('#cash-received').oninput=renderCash;
document.querySelector('#pay').onclick=()=>{if(payMode==='cash'){const amount=moneyInput(document.querySelector('#cash-received').value);if(amount===null)return toast('请输入有效实收金额，最多两位小数');if(amount<total*100)return toast('现金实收不足，请核对');return toast('现金收款登记演示；未调用真实收款确认，不改变已收款状态')}toast('付款入口发起演示；尚无可信支付结果，账单仍未付款')};
document.querySelector('#counted').oninput=e=>{const counted=moneyInput(e.target.value);const diff=counted===null?null:counted-73800;document.querySelector('#variance').textContent=diff===null?'—':diff===0?'账实相符':(diff>0?'盘盈 ¥':'盘亏 ¥')+(Math.abs(diff)/100).toFixed(2)};
document.querySelector('#handover').onclick=()=>{const counted=moneyInput(document.querySelector('#counted').value);if(counted===null)return toast('请填写有效实盘金额');toast(counted===73800?'账实相符；演示待接班人确认及未结事项移交，不执行日结':'存在盘差，需要填写原因并提交授权审批；未完成交班')};
document.querySelectorAll('[data-tip]').forEach(b=>b.onclick=()=>toast(b.dataset.tip));document.querySelector('#reset').onclick=()=>location.reload();render();

// Restaurant-specific appearance preferences; no business state is persisted.
const posRoot=document.querySelector('.pos-v2');
const themeNames={mono:'白底黑按钮',forest:'松绿',indigo:'靛蓝',amber:'暖橙',wine:'绛红',graphite:'石墨'};
function setPosTheme(theme){if(!themeNames[theme])return;posRoot.dataset.posTheme=theme;document.querySelectorAll('[data-theme-choice]').forEach(b=>b.setAttribute('aria-pressed',b.dataset.themeChoice===theme));document.querySelector('#theme-label').textContent='当前：'+themeNames[theme];try{localStorage.setItem('food-pos-prototype-theme-v3',theme)}catch{}}
document.querySelectorAll('[data-theme-choice]').forEach(b=>b.onclick=()=>setPosTheme(b.dataset.themeChoice));
try{const theme=localStorage.getItem('food-pos-prototype-theme-v3');if(themeNames[theme])setPosTheme(theme)}catch{}
document.querySelectorAll('[data-view]').forEach(b=>b.onclick=()=>{posRoot.dataset.menuView=b.dataset.view;document.querySelectorAll('[data-view]').forEach(x=>x.setAttribute('aria-pressed',x===b))});
let menuCategory='all';
function filterPosMenu(){const query=document.querySelector('#search').value.trim().toLowerCase();let count=0;document.querySelectorAll('.cashier-dish').forEach(d=>{d.hidden=!(menuCategory==='all'||d.dataset.menuCat===menuCategory)||!(d.dataset.name.includes(query)||d.dataset.pinyin.includes(query));if(!d.hidden)count++});document.querySelector('#order-no-results').hidden=count>0}
document.querySelector('#search').oninput=filterPosMenu;
document.querySelectorAll('[data-menu-category]').forEach(b=>b.onclick=()=>{menuCategory=b.dataset.menuCategory;document.querySelectorAll('[data-menu-category]').forEach(x=>{x.classList.toggle('on',x===b);x.classList.toggle('is-active',x===b);x.setAttribute('aria-pressed',x===b)});filterPosMenu()});
document.querySelectorAll('[data-cash-key]').forEach(b=>b.onclick=()=>{const input=document.querySelector('#cash-received'),key=b.dataset.cashKey;let next=key==='backspace'?input.value.slice(0,-1):input.value+key;if(next==='.')next='0.';if(next!==''&&!/^\d{0,8}(\.\d{0,2})?$/.test(next))return;input.value=next;renderCash()});
document.querySelectorAll('[data-cash-value]').forEach(b=>b.onclick=()=>{document.querySelector('#cash-received').value=b.dataset.cashValue;renderCash()});
document.querySelector('[data-cash-exact]').onclick=()=>{document.querySelector('#cash-received').value=total.toFixed(2);renderCash()};
document.querySelectorAll('[data-pay]').forEach(b=>b.addEventListener('click',()=>document.querySelector('#s03 .cashier-side').classList.toggle('pos-cash-active',b.dataset.pay==='cash')));

// Compact floor workbench: area/filter/search intersection, scoped selection detail.
function filterFloor(){const query=document.querySelector('#floor-search').value.trim().toLowerCase();const tiles=[...document.querySelectorAll('.floor-table')];let visible=0;tiles.forEach(t=>{const match=t.dataset.floorArea===floorZone&&(floorFilter==='all'||t.dataset.floorState===floorFilter)&&t.dataset.table.toLowerCase().includes(query);t.hidden=!match;if(match)visible++});document.querySelector('#floor-empty').hidden=visible>0;const selected=tiles.find(t=>t.dataset.table===selectedFloorNo);if(selected?.hidden&&visible){selectedFloorNo=tiles.find(t=>!t.hidden).dataset.table;renderFloorSelection()}const zoneTiles=tiles.filter(t=>t.dataset.floorArea===floorZone);document.querySelector('#floor-all-count').textContent=zoneTiles.length;['free','busy','pay'].forEach(state=>document.querySelector('#floor-'+state+'-count').textContent=zoneTiles.filter(t=>t.dataset.floorState===state).length);const heading=document.querySelector('#floor-zone-name');heading.textContent=floorZone==='hall'?'大厅':'包间';const count=document.createElement('span');count.textContent=zoneTiles.length+(floorZone==='hall'?' 桌':' 间');heading.append(count)}
function renderFloorSelection(){const tile=document.querySelector(`.floor-table[data-table="${selectedFloorNo}"]`);if(!tile)return;const state=tile.dataset.floorState,free=state==='free',demo=selectedFloorNo==='A03',amount=demo?total:Number(tile.dataset.amount);document.querySelectorAll('.floor-table').forEach(t=>{t.classList.toggle('is-selected',t===tile);t.setAttribute('aria-pressed',t===tile)});document.querySelector('#floor-detail-no').textContent=selectedFloorNo;const badge=document.querySelector('#floor-detail-state');badge.className='floor-state floor-state--'+state;badge.replaceChildren(document.createElement('i'),document.createTextNode(free?'空闲':state==='pay'?'待结账':'用餐中'));document.querySelector('#floor-detail-people').textContent=tile.dataset.people+(free?' 席可用':' 人用餐');document.querySelector('#floor-detail-time').textContent=free?'等待开台':'已用餐 '+tile.dataset.minutes+' 分钟';document.querySelector('#floor-open-time').textContent=free?'—':demo?'18:04':String(Math.floor((1110-Number(tile.dataset.minutes))/60)).padStart(2,'0')+':'+String((1110-Number(tile.dataset.minutes))%60).padStart(2,'0');document.querySelector('#floor-pay-policy').textContent=free?'开台时确认':'先吃后付';document.querySelector('#floor-waiter').textContent=free?'待分配':demo?'小林':'当班服务员';document.querySelector('#floor-detail-total').textContent=free?'—':'¥'+amount;document.querySelector('#floor-order-action').textContent=free?'＋ 开台点餐':'＋ 点餐 / 加菜';document.querySelector('#floor-checkout-action').hidden=free;document.querySelector('#floor-transfer').hidden=free;document.querySelector('#floor-merge').hidden=free;document.querySelector('#floor-bill-link').hidden=free;const lines=document.querySelector('#floor-bill-lines');lines.replaceChildren();const items=free?[]:demo?[['松鼠桂鱼','×1',88],['米饭','×4',12],['茶位','×4',12],...(total>112?[['后续加菜','',total-112]]:[])]:[['已点菜品汇总','',amount]];document.querySelector('#floor-item-count').textContent=items.length?items.length+' 项':'';items.forEach(([name,quantity,value])=>{const row=document.createElement('div');row.className='floor-bill-row';const text=document.createElement('span');text.textContent=name;const qty=document.createElement('small');qty.textContent=quantity;text.append(qty);const money=document.createElement('b');money.textContent='¥'+value;row.append(text,money);lines.append(row)});const hint=document.createElement('p');hint.className='floor-bill-hint';hint.textContent=free?'当前桌台空闲，开台后即可点餐。':demo?'含茶位费 ¥12 · 尚未付款':'尚未付款 · 请核对桌台账单';lines.append(hint);document.querySelector('[data-floor-live]').textContent='¥'+total}
document.querySelectorAll('.floor-table').forEach(t=>t.onclick=()=>{selectedFloorNo=t.dataset.table;renderFloorSelection()});
document.querySelectorAll('[data-floor-zone]').forEach(b=>b.onclick=()=>{floorZone=b.dataset.floorZone;document.querySelectorAll('[data-floor-zone]').forEach(x=>{x.classList.toggle('is-active',x===b);x.setAttribute('aria-pressed',x===b)});filterFloor()});
document.querySelectorAll('[data-floor-filter]').forEach(b=>b.onclick=()=>{floorFilter=b.dataset.floorFilter;document.querySelectorAll('[data-floor-filter]').forEach(x=>{x.classList.toggle('is-active',x===b);x.setAttribute('aria-pressed',x===b)});filterFloor()});
document.querySelector('#floor-search').oninput=filterFloor;
document.querySelector('#floor-order-action').onclick=()=>{if(selectedFloorNo==='A03')document.querySelector('#s02').scrollIntoView({behavior:'smooth'});else toast(selectedFloorNo+' 已选中；本版完整点餐演示为 A03')};
document.querySelector('#floor-checkout-action').onclick=()=>{if(selectedFloorNo==='A03')document.querySelector('#s03').scrollIntoView({behavior:'smooth'});else toast(selectedFloorNo+' 待核对账单；本版完整收款演示为 A03')};
document.querySelector('#floor-bill-link').onclick=e=>{if(selectedFloorNo!=='A03'){e.preventDefault();toast('当前为 '+selectedFloorNo+' 账单汇总，A03 可查看完整预结单')}};
document.querySelector('#floor-transfer').onclick=()=>toast(selectedFloorNo+' 转台需选择新桌，并校验人数及占用');document.querySelector('#floor-merge').onclick=()=>toast('拼桌分账与并桌合账需分别确认，原订单与资金归属保持不变');document.querySelector('#floor-more').onclick=()=>toast(selectedFloorNo+' 更多操作将在桌台服务详情中展开');filterFloor();renderFloorSelection();

// Clear only unsubmitted draft; committed order and payment state remain unchanged.
document.querySelector('#order-clear').onclick=()=>{Object.keys(cart).forEach(k=>cart[k]=0);render();toast('本次草稿已清空，已点菜品保持不变')};
document.querySelector('#order-remark').onclick=()=>toast('整单备注入口：可补充上菜顺序或服务要求；做法请在对应菜品确认');
document.querySelector('#order-discount').onclick=()=>toast('优惠需授权并重新报价，不在此直接修改订单金额');
