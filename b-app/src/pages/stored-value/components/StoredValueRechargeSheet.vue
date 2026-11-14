<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {onHide,onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {selectStoredValueMember} from '@/api/member-scopes';
import {useMerchantStore} from '@/stores/merchant';
import {money} from '@shared/utils/money';
import {storedValuePending,type SvPending} from '@/shared/stored-value-pending';
import {idempotencyKey} from '@shared/net/http-client';
import {isBrandOffer,offerKey,offerName,type SvRechargeOffer,type SvProgress,type SvSelectedCustomer,type SvSelectionSource} from '@/api/stored-value-types';

const props=defineProps<{visible:boolean;source?:SvSelectionSource;identified?:SvSelectedCustomer;planNo?:string}>();
const emit=defineEmits<{close:[];credited:[SvProgress]}>();
const merchant=useMerchantStore();const {t}=useI18n();
type Pending=SvPending;
const customer=ref<SvSelectedCustomer|null>(null),plans=ref<SvRechargeOffer[]>([]),selected=ref<SvRechargeOffer|null>(null);
const pending=ref<Pending|null>(null),progress=ref<SvProgress|null>(null);
const scopeType=ref<'STORE'|'BRAND'>('STORE'),hasBrand=ref(false);let customerScope='';
const scopeTabs=computed(()=>[{key:'STORE',label:t('issuer.storeScope')},{key:'BRAND',label:t('issuer.brandScope')}]);
const busy=ref(false),loading=ref(false),failed=ref(false),error=ref(''),cursor=ref<string|null>(null),resumed=ref(false);
const recoveryChoices=ref<Array<{scope:string;pending:Pending}>>([]);
let recovery=storedValuePending('',''),pendingScope='',selectionReady=false;
let generation=0,store='',foreground=true,timer:ReturnType<typeof setTimeout>|undefined,emitted='';
const credited=computed(()=>progress.value?.status==='CREDITED');
const terminal=computed(()=>!!progress.value&&['CREDITED','CANCELLED','REJECTED'].includes(progress.value.status));
const allowed=computed(()=>merchant.can('biz:customer')&&merchant.can('pay:sv:recharge:create')&&merchant.can('pay:sv:recharge:receive'));
const locked=computed(()=>busy.value||loading.value||!!pending.value||store!==merchant.storeNo);
const terminalTitles:Record<string,string>={CREDITED:'credited',CANCELLED:'cancelled',REJECTED:'rejected'};
const title=computed(()=>t(`storedValue.${progress.value?(terminalTitles[progress.value.status]||'processing'):'selectPlan'}`));
const action=computed(()=>t(terminal.value?'storedValue.finish':progress.value?'storedValue.refresh':'storedValue.offlineConfirm'));
function live(token:number){return props.visible&&foreground&&generation===token&&store===merchant.storeNo;}
function persist(){if(pending.value)recovery.write(pendingScope,pending.value);}
function accept(r:SvProgress){
 progress.value=r;if(pending.value){pending.value.progress=r;persist();}
 if(terminal.value&&pending.value)recovery.remove(pendingScope,pending.value);
 if(r.status==='CREDITED'&&emitted!==r.rechargeNo){emitted=r.rechargeNo;emit('credited',r);}
}
function schedule(){clearTimeout(timer);if(props.visible&&foreground&&!terminal.value)timer=setTimeout(()=>void refresh(),3000);}
function sameCustomer(p:Pending,c:SvSelectedCustomer){
 return !!((c.memberNo&&p.customer.memberNo===c.memberNo)||(c.account?.accountRef&&p.customer.account?.accountRef===c.account.accountRef));
}
async function restore(p:Pending,scope:string,token:number){
 pendingScope=scope;pending.value=p;customer.value=p.customer;selected.value=p.plan;scopeType.value=p.body.scopeType||'STORE';progress.value=p.progress;resumed.value=true;
 try{
  const result=await api.mSvResolveRecharge(p.body,p.key);if(!live(token))return false;
  if(result.found&&result.recharge){accept(result.recharge);if(!terminal.value)schedule();return true;}
  // Seal a never-committed command before allowing a fresh one; a late original cannot subsequently credit.
  // The server refuses cancellation after receipt, in which case we re-read the original instead.
  try{const cancelled=await api.mSvCancelCreate(p.body,p.key);if(!live(token))return false;
   if(cancelled.status!=='CANCELLED'){accept(cancelled);return true;}
   recovery.remove(scope,p);pending.value=null;resumed.value=false;
   if(!props.source&&!props.identified){progress.value=cancelled;return true;}
   progress.value=null;selected.value=null;return false;
  }catch{
   const current=await api.mSvResolveRecharge(p.body,p.key);if(!live(token))return false;
   if(current.found&&current.recharge){accept(current.recharge);if(!terminal.value)schedule();return true;}
   throw Error(t('storedValue.networkUnknown'));
  }
 }catch(e){if(live(token))error.value=(e as Error).message;return true;}
}
async function load(){
 const token=++generation;clearTimeout(timer);loading.value=true;busy.value=false;failed.value=false;error.value='';
 store=merchant.storeNo;
 customer.value=null;plans.value=[];selected.value=null;pending.value=null;progress.value=null;resumed.value=false;recoveryChoices.value=[];cursor.value=null;selectionReady=false;
 try{
  const actor=await api.mManagementContext();if(!live(token))return;recovery=storedValuePending(merchant.profile?.merchantNo||'',store,actor.actorKey);
  if(!props.source&&!props.identified){
   const all=Object.entries(recovery.entries()).map(([scope,pending])=>({scope,pending}));const old=recovery.legacy();if(old)all.push({scope:'legacy',pending:old});
   if(all.length>1){recoveryChoices.value=all;return;}
   if(all.length===1){await restore(all[0]!.pending,all[0]!.scope,token);return;}
   throw Error(t('storedValue.chooseMember'));
  }
  const c=props.identified??await selectStoredValueMember(store,props.source!);if(!live(token))return;
  customerScope=c.pendingScope||JSON.stringify(props.source||c.customerRef);pendingScope=customerScope+':'+scopeType.value;
  const original=recovery.entries()[pendingScope];const old=recovery.legacy();
  if(original||(old&&sameCustomer(old,c))){if(await restore(original||old!,pendingScope,token))return;if(!live(token))return;}
  customer.value=c;const result=await api.mSvRechargeOptions({storeNo:store,customerRef:c.customerRef,scopeType:scopeType.value});if(!live(token))return;
  if(!result.issuerOptions.legacyMode&&!result.issuerOptions.issuers.some(i=>i.canRecharge))throw Error(t('issuer.blockers.'+(result.issuerOptions.issuers[0]?.unavailableReason||'ISSUANCE_NOT_ENABLED')));
  hasBrand.value=result.issuerOptions.issuers.some(i=>i.scopeType==='BRAND');
  const page=scopeType.value==='BRAND'?result.brandOptions:result.plans;if(!page)throw Error(t('storedValue.empty'));
  plans.value=page.records;cursor.value=page.nextCursor;selectionReady=true;
  if(props.planNo)selected.value=plans.value.find(p=>!isBrandOffer(p)&&p.planNo===props.planNo)||null;
 }catch(e){if(live(token)){failed.value=true;error.value=(e as Error).message;}}
 finally{if(generation===token)loading.value=false;}
}
async function chooseScope(value:string){
 if(locked.value||!customer.value||!['STORE','BRAND'].includes(value)||value===scopeType.value)return;
 scopeType.value=value as 'STORE'|'BRAND';selected.value=null;plans.value=[];cursor.value=null;error.value='';failed.value=false;
 const token=++generation;loading.value=true;pendingScope=customerScope+':'+scopeType.value;
 try{
  const original=recovery.entries()[pendingScope];if(original&&await restore(original,pendingScope,token))return;
  const result=await api.mSvRechargeOptions({storeNo:store,customerRef:customer.value.customerRef,scopeType:scopeType.value});
  if(!live(token))return;const page=scopeType.value==='BRAND'?result.brandOptions:result.plans;
  if(!page)throw Error(t('storedValue.empty'));plans.value=page.records;cursor.value=page.nextCursor;
 }catch(e){if(live(token)){failed.value=true;error.value=(e as Error).message;}}finally{if(token===generation)loading.value=false;}
}
async function chooseRecovery(choice:{scope:string;pending:Pending}){if(busy.value||loading.value)return;const token=generation;recoveryChoices.value=[];loading.value=true;await restore(choice.pending,choice.scope,token);if(token===generation)loading.value=false;}
async function more(){if(locked.value||!cursor.value)return;const token=generation;loading.value=true;try{if(!customer.value)return;const result=await api.mSvRechargeOptions({storeNo:store,customerRef:customer.value.customerRef,scopeType:scopeType.value,cursor:cursor.value});const page=scopeType.value==='BRAND'?result.brandOptions:result.plans;if(!page)return;if(live(token)){plans.value.push(...page.records);cursor.value=page.nextCursor;}}catch(e){if(live(token))error.value=(e as Error).message;}finally{if(generation===token)loading.value=false;}}
async function submit(){
 if(busy.value||loading.value||!allowed.value||store!==merchant.storeNo||!selected.value||!customer.value)return;
 const token=generation;busy.value=true;error.value='';
 try{
  if(!pending.value){
   // Refresh selection only before creating a durable command. An unknown original keeps its body/key intact.
   if(Date.parse(customer.value.expiresAt)<=Date.now()){
    if(!props.source)throw Error(t('storedValue.referenceExpired'));
    const c=await selectStoredValueMember(store,props.source);if(!live(token))return;customer.value=c;
   }
   pending.value={body:{storeNo:store,customerRef:customer.value.customerRef,...(isBrandOffer(selected.value)?{scopeType:'BRAND' as const,planNo:null,versionNo:null,optionRef:selected.value.optionRef}:{scopeType:'STORE' as const,planNo:selected.value.planNo,versionNo:selected.value.versionNo})},key:idempotencyKey(),customer:customer.value,plan:selected.value,progress:null};persist();
  }
  const p=pending.value;const r=await api.mSvConfirmOffline(p.body,p.key);if(!live(token))return;accept(r);if(!terminal.value)schedule();
 }catch(e){if(live(token)){
  error.value=(e as Error).message;
  const p=pending.value;
  if(p&&typeof (e as {code?:number}).code==='number'&&(e as {code:number}).code>0){
   try{const original=await api.mSvResolveRecharge(p.body,p.key);if(live(token)&&!original.found){const sealed=await api.mSvCancelCreate(p.body,p.key);if(live(token)){if(sealed.status==='CANCELLED'){recovery.remove(pendingScope,p);pending.value=null;}else accept(sealed);}}else if(live(token)&&original.recharge)accept(original.recharge);}catch{error.value+=` · ${t('storedValue.networkUnknown')}`;}
  }else error.value+=` · ${t('storedValue.networkUnknown')}`;
 }}
 finally{if(generation===token)busy.value=false;}
}
async function refresh(){
 const p=pending.value;if(!p?.progress?.rechargeNo||busy.value||store!==merchant.storeNo)return;
 const token=generation;busy.value=true;error.value='';try{const r=await api.mSvIssuerRecharge(p.progress.rechargeNo,store);if(live(token)){accept(r);if(!terminal.value)schedule();}}
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
 if(returning&&(!customer.value||!selectionReady)&&!pending.value)void load();
 else if(progress.value&&!terminal.value)schedule();
});
onUnmounted(()=>{generation++;clearTimeout(timer);});
</script>
<template>
 <sh-sheet :visible="props.visible" :title="title" fit @close="close">
  <view class="sv-sheet-list" :class="{'sv-sheet-list--plans':!progress}">
   <view v-for="choice in recoveryChoices" :key="choice.scope" role="button" class="sh-card" @tap="chooseRecovery(choice)"><text class="txt-strong">{{choice.pending.customer.displayName}}</text><text class="sh-muted">{{choice.pending.customer.phoneMasked}}</text></view>
   <view v-if="customer" class="sh-row sh-row--between"><text class="txt-strong">{{customer.displayName}}</text><text class="sh-muted sh-num">{{customer.phoneMasked}}</text></view>
   <view v-if="pending&&!progress" class="sh-card"><text class="txt-strong">{{offerName(pending.plan)}}</text><sh-kv :label="t('storedValue.total')" between>{{money(pending.plan.totalAmountMinor,pending.plan.currency)}}</sh-kv></view>
   <text v-if="resumed&&!terminal" class="sh-hint">{{t('storedValue.savePending')}}</text>
   <sh-empty v-if="loading||failed" :pending="loading" :failed="failed" compact @retry="load"/>
   <sh-tabs v-if="hasBrand&&!progress" :items="scopeTabs" :active="scopeType" @change="chooseScope"/>
   <template v-if="!progress&&!failed">
    <view v-for="plan in plans" :key="offerKey(plan)" data-testid="recharge-plan" role="radio" :aria-checked="!!selected&&offerKey(selected)===offerKey(plan)" class="sh-card sv-plan" :class="{'sv-plan--selected':!!selected&&offerKey(selected)===offerKey(plan)}" @tap="!locked&&(selected=plan)">
     <view class="sh-row sh-row--between"><text class="txt-strong">{{offerName(plan)}}</text><text class="txt-price sh-num">{{money(plan.paidAmountMinor,plan.currency)}}</text></view>
     <text class="sh-muted">{{t('storedValue.total')}} {{money(plan.totalAmountMinor,plan.currency)}}</text>
    </view>
    <sh-empty v-if="!loading&&!plans.length&&!pending&&!recoveryChoices.length" :text="t('storedValue.empty')" compact/>
    <view v-if="cursor&&!pending" role="button" class="sh-link sh-hit" @tap="more">{{t('storedValue.more')}}</view>
   </template>
   <view v-if="progress" class="sh-card">
    <text class="txt-strong">{{t(`storedValue.${progress.status==='CREDITED'?'credited':progress.status==='RECEIVED'?'received':progress.status==='CANCELLED'?'cancelled':progress.status==='REJECTED'?'rejected':'processing'}`)}}</text>
    <sh-kv v-if="credited" :label="t('storedValue.total')" between>{{money(progress.creditedTotalMinor||0,progress.currency)}}</sh-kv>
   </view>
   <text v-if="!allowed" class="sh-hint">{{t('storedValue.noPermission')}}</text>
   <text v-if="error" class="sh-hint">{{error}}</text><view v-if="pending&&!terminal&&(!progress||['WAIT_COLLECTION','SUBMITTED'].includes(progress.status))" role="button" class="sh-link sh-hit" @tap="cancelPending">{{t('storedValue.cancelRecharge')}}</view>
  </view>
  <template #foot><view data-testid="confirm-offline" role="button" class="sh-btn" :class="{'is-disabled':busy||loading||!allowed||!selected||failed}" :aria-disabled="busy||loading||!allowed||!selected||failed" @tap="perform">{{action}}<template v-if="selected&&!progress"> · {{money(selected.paidAmountMinor,selected.currency)}}</template></view></template>
 </sh-sheet>
</template>
<style scoped>
.sv-sheet-list{display:flex;flex-direction:column;gap:var(--sh-gap-block)}
.sv-sheet-list--plans{min-height:32vh;padding-block:var(--sh-gap-block)}
.sv-plan{background:var(--sh-bg)}
.sv-plan--selected{background:var(--sh-primary-tint)}
</style>
