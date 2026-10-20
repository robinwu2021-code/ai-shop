<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {onHide,onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {money} from '@shared/utils/money';
import {idempotencyKey} from '@shared/net/http-client';
import type {SvPlan,SvProgress,SvRechargeCreate,SvSelectedCustomer,SvSelectionSource} from '@/api/stored-value-types';

const props=defineProps<{visible:boolean;source?:SvSelectionSource;identified?:SvSelectedCustomer;planNo?:string}>();
const emit=defineEmits<{close:[];credited:[SvProgress]}>();
const merchant=useMerchantStore();const {t}=useI18n();
interface Pending {body:SvRechargeCreate;key:string;customer:SvSelectedCustomer;plan:SvPlan;progress:SvProgress|null}
const customer=ref<SvSelectedCustomer|null>(null),plans=ref<SvPlan[]>([]),selected=ref<SvPlan|null>(null);
const pending=ref<Pending|null>(null),progress=ref<SvProgress|null>(null);
const busy=ref(false),loading=ref(false),failed=ref(false),error=ref(''),cursor=ref<string|null>(null),resumed=ref(false);
let generation=0,store='',storage='',foreground=true,timer:ReturnType<typeof setTimeout>|undefined,emitted='';
const credited=computed(()=>progress.value?.status==='CREDITED');
const terminal=computed(()=>!!progress.value&&['CREDITED','CANCELLED','REJECTED'].includes(progress.value.status));
const allowed=computed(()=>merchant.can('biz:customer')&&merchant.can('pay:sv:recharge:create')&&merchant.can('pay:sv:recharge:receive'));
const locked=computed(()=>busy.value||loading.value||!!pending.value||store!==merchant.storeNo);
const terminalTitles:Record<string,string>={CREDITED:'credited',CANCELLED:'cancelled',REJECTED:'rejected'};
const title=computed(()=>t(`storedValue.${progress.value?(terminalTitles[progress.value.status]||'processing'):'selectPlan'}`));
const action=computed(()=>t(terminal.value?'storedValue.finish':progress.value?'storedValue.refresh':'storedValue.offlineConfirm'));
function live(token:number){return props.visible&&foreground&&generation===token&&store===merchant.storeNo;}
function persist(){if(pending.value)uni.setStorageSync(storage,JSON.stringify(pending.value));}
function accept(r:SvProgress){
 progress.value=r;if(pending.value){pending.value.progress=r;persist();}
 if(terminal.value)uni.removeStorageSync(storage);
 if(r.status==='CREDITED'&&emitted!==r.rechargeNo){emitted=r.rechargeNo;emit('credited',r);}
}
function schedule(){clearTimeout(timer);if(props.visible&&foreground&&!terminal.value)timer=setTimeout(()=>void refresh(),3000);}
async function load(){
 const token=++generation;clearTimeout(timer);loading.value=true;busy.value=false;failed.value=false;error.value='';
 store=merchant.storeNo;storage=`sv.offline:${merchant.profile?.merchantNo}:${store}`;
 customer.value=null;plans.value=[];selected.value=null;pending.value=null;progress.value=null;resumed.value=false;
 try{
  const raw=uni.getStorageSync(storage);
  if(raw){const p=JSON.parse(String(raw)) as Pending;if(p.body?.storeNo===store&&p.key&&p.plan?.planNo===p.body.planNo&&p.customer?.customerRef===p.body.customerRef){
   pending.value=p;customer.value=p.customer;selected.value=p.plan;progress.value=p.progress;resumed.value=true;
   if(p.progress?.rechargeNo)await refresh();return;
  }}
  if(!props.source&&!props.identified)throw Error(t('storedValue.chooseMember'));
  const c=props.identified??await api.mSvSelectCustomer({storeNo:store,source:props.source!});
  const page=await api.mSvPlans({storeNo:store,status:'ACTIVE'});if(!live(token))return;
  customer.value=c;plans.value=page.records;cursor.value=page.nextCursor;
  if(props.planNo)selected.value=plans.value.find(p=>p.planNo===props.planNo)||null;
 }catch(e){if(live(token)){failed.value=true;error.value=(e as Error).message;}}
 finally{if(generation===token)loading.value=false;}
}
async function more(){if(locked.value||!cursor.value)return;const token=generation;loading.value=true;try{const page=await api.mSvPlans({storeNo:store,status:'ACTIVE',cursor:cursor.value});if(live(token)){plans.value.push(...page.records);cursor.value=page.nextCursor;}}catch(e){if(live(token))error.value=(e as Error).message;}finally{if(generation===token)loading.value=false;}}
async function submit(){
 if(busy.value||loading.value||!allowed.value||store!==merchant.storeNo||!selected.value||!customer.value)return;
 const token=generation;busy.value=true;error.value='';
 try{
  if(!pending.value){
   // Refresh selection only before creating a durable command. An unknown original keeps its body/key intact.
   if(Date.parse(customer.value.expiresAt)<=Date.now()){
    if(!props.source)throw Error(t('storedValue.referenceExpired'));
    const c=await api.mSvSelectCustomer({storeNo:store,source:props.source});if(!live(token))return;customer.value=c;
   }
   pending.value={body:{storeNo:store,customerRef:customer.value.customerRef,planNo:selected.value.planNo,versionNo:selected.value.versionNo},key:idempotencyKey(),customer:customer.value,plan:selected.value,progress:null};persist();
  }
  const p=pending.value;const r=await api.mSvConfirmOffline(p.body,p.key);if(!live(token))return;accept(r);if(!terminal.value)schedule();
 }catch(e){if(live(token))error.value=`${(e as Error).message} · ${t('storedValue.networkUnknown')}`;}
 finally{if(generation===token)busy.value=false;}
}
async function refresh(){
 const p=pending.value;if(!p?.progress?.rechargeNo||busy.value||store!==merchant.storeNo)return;
 const token=generation;busy.value=true;error.value='';try{const r=await api.mSvRecharge(p.progress.rechargeNo);if(live(token)){accept(r);if(!terminal.value)schedule();}}
 catch(e){if(live(token)){error.value=(e as Error).message;schedule();}}
 finally{if(generation===token)busy.value=false;}
}
async function cancelPending(){
 const p=pending.value;if(!p||busy.value||store!==merchant.storeNo)return;const token=generation;busy.value=true;error.value='';
 try{const r=await api.mSvCancelCreate(p.body,p.key);if(live(token))accept(r);}catch(e){if(live(token))error.value=(e as Error).message;}
 finally{if(generation===token)busy.value=false;}
}
function perform(){if(terminal.value){close();return;}if(progress.value&&['RECEIVED','PROCESSING'].includes(progress.value.status))void refresh();else if(!terminal.value)void submit();}
function close(){if(busy.value)return;emit('close');}
watch(()=>JSON.stringify([props.visible,merchant.storeNo,props.source?.type,props.source&&('memberNo' in props.source?props.source.memberNo:props.source.accountRef),props.identified?.customerRef]),()=>{
 if(props.visible)void load();else{generation++;clearTimeout(timer);}
},{immediate:true});
onHide(()=>{foreground=false;clearTimeout(timer);});
onShow(()=>{
 const returning=!foreground;foreground=true;
 if(!props.visible)return;
 // A hidden first response is deliberately ignored; restart that read when returning.
 if(returning&&!customer.value&&!pending.value)void load();
 else if(progress.value&&!terminal.value)schedule();
});
onUnmounted(()=>{generation++;clearTimeout(timer);});
</script>
<template>
 <sh-sheet :visible="props.visible" :title="title" fit @close="close">
  <view class="sv-sheet-list">
   <view v-if="customer" class="sh-row sh-row--between"><text class="txt-strong">{{customer.displayName}}</text><text class="sh-muted sh-num">{{customer.phoneMasked}}</text></view>
   <view v-if="pending&&!progress" class="sh-card"><text class="txt-strong">{{pending.plan.name}}</text><sh-kv :label="t('storedValue.total')" between>{{money(pending.plan.totalAmountMinor,pending.plan.currency)}}</sh-kv></view>
   <text v-if="resumed&&!terminal" class="sh-hint">{{t('storedValue.savePending')}}</text>
   <sh-empty v-if="loading||failed" :pending="loading" :failed="failed" compact @retry="load"/>
   <template v-if="!progress&&!failed">
    <view v-for="plan in plans" :key="plan.planNo" data-testid="recharge-plan" role="radio" :aria-checked="selected?.planNo===plan.planNo" class="sh-card sv-plan" :class="{'sv-plan--selected':selected?.planNo===plan.planNo}" @tap="!locked&&(selected=plan)">
     <view class="sh-row sh-row--between"><text class="txt-strong">{{plan.name}}</text><text class="txt-price sh-num">{{money(plan.paidAmountMinor,plan.currency)}}</text></view>
     <text class="sh-muted">{{t('storedValue.total')}} {{money(plan.totalAmountMinor,plan.currency)}}</text>
    </view>
    <sh-empty v-if="!loading&&!plans.length&&!pending" :text="t('storedValue.empty')" compact/>
    <view v-if="cursor&&!pending" role="button" class="sh-link sh-hit" @tap="more">{{t('storedValue.more')}}</view>
   </template>
   <view v-if="progress" class="sh-card">
    <text class="txt-strong">{{t(`storedValue.${progress.status==='CREDITED'?'credited':progress.status==='RECEIVED'?'received':progress.status==='CANCELLED'?'cancelled':progress.status==='REJECTED'?'rejected':'processing'}`)}}</text>
    <text class="sh-muted sh-num">{{progress.rechargeNo}}</text>
    <sh-kv v-if="credited" :label="t('storedValue.total')" between>{{money(progress.creditedTotalMinor||0,progress.currency)}}</sh-kv>
   </view>
   <text v-if="selected&&!progress" class="sh-hint">{{t('storedValue.offlineHint',{amount:money(selected.paidAmountMinor,selected.currency)})}}</text>
   <text v-if="!allowed" class="sh-hint">{{t('storedValue.noPermission')}}</text>
   <text v-if="error" class="sh-hint">{{error}}</text><view v-if="pending&&!terminal&&(!progress||['WAIT_COLLECTION','SUBMITTED'].includes(progress.status))" role="button" class="sh-link sh-hit" @tap="cancelPending">{{t('storedValue.cancelRecharge')}}</view>
  </view>
  <template #foot><view data-testid="confirm-offline" role="button" class="sh-btn" :class="{'is-disabled':busy||loading||!allowed||!selected||failed}" :aria-disabled="busy||loading||!allowed||!selected||failed" @tap="perform">{{busy?t('storedValue.processing'):action}}<template v-if="selected&&!progress"> · {{money(selected.paidAmountMinor,selected.currency)}}</template></view></template>
 </sh-sheet>
</template>
<style scoped>
.sv-sheet-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}
.sv-plan{background:var(--sh-bg)}
.sv-plan--selected{background:var(--sh-primary-tint)}
</style>
