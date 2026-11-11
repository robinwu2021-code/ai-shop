<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {idempotencyKey} from '@shared/net/http-client';
import {restoreRpCommand,resumeRpCommand,rpStorageKey,rpQuantity,sameRpScope,rpAction} from '@/shared/replenishment-command';
import type {RpCommand,RpContext,RpPage,RpReconciliation,RpScope,RpView} from '@/api/replenishment';
const {t}=useI18n(),merchant=useMerchantStore();
const context=ref<RpContext|null>(null),page=ref<RpPage|null>(null),checks=ref<RpReconciliation|null>(null);
const pending=ref<RpCommand[]>([]),busy=ref(false),loading=ref(false),failed=ref(false),error=ref(''),selected=ref<RpView|null>(null);
let generation=0,account='';
const actions=['register','receive','close','return-dispatch','return-ack','manual-receipt','manual-register'] as const;
const action=ref<typeof actions[number]>('register');const actionLabels=computed(()=>actions.map(k=>t(`replenishment.action.${k}`)));
const no=ref(''),documentNo=ref(''),line=ref('1'),item=ref(''),unit=ref(''),factor=ref('1'),quantity=ref(''),reason=ref(''),movement=ref('');
const hasSource=computed(()=>['register','manual-register','return-ack'].includes(action.value));
const hasMapping=computed(()=>action.value==='register'||action.value==='manual-register');
const hasQuantity=computed(()=>action.value==='receive'||action.value==='return-dispatch');
const hasReason=computed(()=>action.value==='close'||action.value==='return-dispatch');
const hasMovement=computed(()=>action.value==='manual-register'||action.value==='manual-receipt');
function scope():RpScope|null{return context.value&&context.value.storeNo===merchant.storeNo&&account===merchant.profile?.merchantNo?context.value:null;}
function message(e:unknown){const key=(e as Error).message;return key.startsWith('replenishment.')?t(key):key;}
function readPending(s:RpScope){try{const rows=JSON.parse(String(uni.getStorageSync(rpStorageKey(s))||'[]'));return Array.isArray(rows)?rows.map(v=>restoreRpCommand(JSON.stringify(v),s)).filter((v):v is RpCommand=>!!v):[];}catch{return [];}}
function persist(s:RpScope,rows:RpCommand[]){if(rows.length)uni.setStorageSync(rpStorageKey(s),JSON.stringify(rows));else uni.removeStorageSync(rpStorageKey(s));if(sameRpScope(s,scope()))pending.value=rows;}
async function load(){
 const token=++generation,store=merchant.storeNo,actor=merchant.profile?.merchantNo;loading.value=true;error.value='';
 try{const c=await api.mRpContext();if(token!==generation||store!==merchant.storeNo||actor!==merchant.profile?.merchantNo)return;
  if(c.storeNo!==store)throw Error('replenishment.scopeChanged');context.value=c;account=actor||'';pending.value=readPending(c);
  const rows=await api.mRpList(c,{size:20});if(token!==generation||!sameRpScope(c,scope()))return;page.value=rows;failed.value=false;
 }catch(e){if(token===generation){failed.value=true;error.value=message(e);}}finally{if(token===generation)loading.value=false;}
}
async function more(){const s=scope(),next=page.value?.nextCursor,through=page.value?.throughId;if(!s||next==null||loading.value)return;loading.value=true;
 try{const rows=await api.mRpList(s,{after:next,through,size:20});if(sameRpScope(s,scope()))page.value={...rows,items:[...(page.value?.items||[]),...rows.items]};}
 catch(e){if(sameRpScope(s,scope()))error.value=message(e);}finally{if(sameRpScope(s,scope()))loading.value=false;}
}
function select(row:RpView){selected.value=row;no.value=row.replenishmentNo;quantity.value='';checks.value=null;}
function required(v:string){if(!v.trim())throw Error('replenishment.required');return v.trim();}
function origin(){const n=Number(line.value);if(!Number.isInteger(n)||n<1||n>2147483647)throw Error('replenishment.invalidLine');return {documentNo:required(documentNo.value),lineNo:n};}
function operation():RpCommand {
 const s=scope();if(!s)throw Error('replenishment.scopeChanged');const base={scope:s,key:idempotencyKey()};
 const mapping=()=>({itemNo:required(item.value),baseUnit:required(unit.value),factor:rpQuantity(factor.value)});
 switch(action.value){
  case 'register':return {...base,kind:'register',body:{...origin(),mapping:mapping()}};
  case 'manual-register':return {...base,kind:'manual-register',body:{...origin(),mapping:mapping(),movementNo:required(movement.value)}};
  case 'receive':return {...base,kind:'receive',no:required(no.value),body:{cumulativeQuantity:rpQuantity(quantity.value)}};
  case 'close':return {...base,kind:'close',no:required(no.value),body:{reason:required(reason.value)}};
  case 'return-dispatch':return {...base,kind:'return-dispatch',no:required(no.value),body:{cumulativeQuantity:rpQuantity(quantity.value),reason:required(reason.value)}};
  case 'return-ack':return {...base,kind:'return-ack',no:required(no.value),body:origin()};
  case 'manual-receipt':return {...base,kind:'manual-receipt',no:required(no.value),body:{movementNo:required(movement.value)}};
 }
}
const intent=(c:RpCommand)=>c.kind==='configure'?`${c.kind}:${c.body.enabled}`:'no' in c?`${c.kind}:${c.no}`:`${c.kind}:${c.body.documentNo}:${c.body.lineNo}`;
async function run(op:RpCommand){
 if(busy.value||!sameRpScope(op.scope,scope()))return;busy.value=true;error.value='';const attempted=!!op.attempted;
 try{
  const result=await resumeRpCommand(api,op,scope,original=>{original.attempted=true;persist(original.scope,readPending(original.scope).map(p=>p.key===original.key?original:p));});
  persist(op.scope,readPending(op.scope).filter(p=>p.key!==op.key));
  if(sameRpScope(op.scope,scope())){if('replenishmentNo' in result)select(result);await load();}
 }catch(e){
  // Only a first request's explicit rejection is definite. An earlier unknown attempt keeps its original key.
  const status=(e as {httpStatus?:number}).httpStatus,code=(e as {code?:number}).code;
  if(!attempted&&((status&&[400,403,404,409].includes(status))||(code&&[10400,10403,10404,10409,70006].includes(code)))){persist(op.scope,readPending(op.scope).filter(p=>p.key!==op.key));if(sameRpScope(op.scope,scope()))await load();}
  if(sameRpScope(op.scope,scope()))error.value=message(e);
 }finally{busy.value=false;}
}
function enqueue(op:RpCommand){const all=readPending(op.scope);if(all.some(p=>intent(p)===intent(op)))throw Error('replenishment.pendingFirst');persist(op.scope,[...all,op]);void run(op);}
function submit(){try{enqueue(operation());}catch(e){error.value=message(e);}}
function configure(enabled:boolean){try{const s=scope(),c=context.value?.connection;if(!s||!c)throw Error('replenishment.scopeChanged');if(!Number.isSafeInteger(c.revision))throw Error('replenishment.refresh');enqueue({scope:s,key:idempotencyKey(),kind:'configure',body:{enabled,expectedVersion:c.revision,reason:required(reason.value)}});}catch(e){error.value=message(e);}}
async function seal(op:RpCommand){
 if(busy.value||!sameRpScope(op.scope,scope()))return;busy.value=true;error.value='';
 try{const state=await api.mRpCancel(op.scope,rpAction(op),op.key,reason.value.trim()||'OPERATOR_STOP_PENDING_REQUEST');
  if(!sameRpScope(op.scope,scope()))return;
  if(state.status!=='CANCELLED'&&state.status!=='COMPLETED')throw Error('replenishment.unknown');
  persist(op.scope,readPending(op.scope).filter(p=>p.key!==op.key));if(state.original)select(state.original);await load();
  error.value=t(state.status==='CANCELLED'?'replenishment.cancelled':'replenishment.alreadyCompleted');
 }catch(e){if(sameRpScope(op.scope,scope()))error.value=message(e);}finally{busy.value=false;}
}
async function reconcile(){const s=scope();if(!s||!no.value||busy.value)return;busy.value=true;error.value='';try{const result=await api.mRpReconciliation(s,no.value);if(sameRpScope(s,scope()))checks.value=result;}catch(e){if(sameRpScope(s,scope()))error.value=message(e);}finally{busy.value=false;}}
watch(()=>[merchant.storeNo,merchant.profile?.merchantNo],()=>{generation++;context.value=null;page.value=null;selected.value=null;checks.value=null;pending.value=[];loading.value=false;error.value='';no.value='';});
onShow(async()=>{await merchant.ensureStores();await merchant.ensureScope();await load();});
</script>
<template>
<sh-scaffold title-key="replenishment.title" :title-suffix="merchant.currentStore?.name" :denied="!merchant.can('biz:stock')" :failed="failed" :pending="!context&&!failed" @retry="load">
 <view v-if="context" class="sh-card rp-card">
  <text class="txt-title">{{t(context.connection.enabled?'replenishment.connected':'replenishment.disconnected')}}</text>
  <text class="sh-hint">{{t('replenishment.localUnchanged')}}</text><button class="sh-btn sh-btn--soft" :disabled="busy||loading" @tap="load">{{t('replenishment.refresh')}}</button>
  <input v-model="reason" class="rp-input" :placeholder="t('replenishment.reason')" :disabled="busy"/>
  <button class="sh-btn sh-btn--soft" :disabled="busy" @tap="configure(!context.connection.enabled)">{{t(context.connection.enabled?'replenishment.disable':'replenishment.enable')}}</button>
  <button v-if="!context.connection.enabled&&pending.some(p=>p.kind==='configure'&&p.body.enabled)" class="sh-btn sh-btn--soft" :disabled="busy" @tap="configure(false)">{{t('replenishment.explicitStop')}}</button>
 </view>
 <view v-if="pending.length" class="sh-card rp-card">
  <text class="txt-title">{{t('replenishment.pending')}}</text><text class="sh-hint">{{t('replenishment.pendingHint')}}</text>
  <view v-for="p in pending" :key="p.key" class="rp-row"><text>{{t(`replenishment.action.${p.kind}`)}} {{'no' in p?p.no:''}}</text><button class="sh-btn sh-btn--soft" :disabled="busy" @tap="run(p)">{{t('replenishment.resume')}}</button><button class="sh-btn sh-btn--soft" :disabled="busy" @tap="seal(p)">{{t('replenishment.stopPending')}}</button></view>
 </view>
 <view v-if="page" class="sh-card rp-card">
  <text class="txt-title">{{t('replenishment.attention')}}</text><text v-if="!page.items.length" class="sh-hint">{{t('replenishment.empty')}}</text>
  <view v-for="row in page.items" :key="row.replenishmentNo" class="rp-row" @tap="select(row)">
   <view><text class="sh-num">{{row.sourceDocument}} / {{row.sourceLine}}</text><text>{{t(`replenishment.state.${row.status}`)}}</text></view>
   <text>{{t('replenishment.received')}} {{row.receivedQuantity}} / {{row.expectedQuantity}} {{row.baseUnit}}</text>
   <text>{{t('replenishment.difference')}} {{row.differenceQuantity}} · {{t('replenishment.returnPending')}} {{row.returnedQuantity}} / {{row.returnAcceptedQuantity}}</text>
  </view>
  <button class="sh-btn sh-btn--soft" v-if="page.nextCursor!=null" :disabled="loading||busy" @tap="more">{{t('replenishment.more')}}</button>
 </view>
 <view v-if="context" class="sh-card rp-card">
  <picker :range="actionLabels" @change="action=actions[Number($event.detail.value)]||'register'"><view class="rp-input">{{t(`replenishment.action.${action}`)}}</view></picker>
  <input v-if="!hasMapping" v-model="no" class="rp-input" :placeholder="t('replenishment.originalNo')" :disabled="busy"/>
  <input v-if="hasSource" v-model="documentNo" class="rp-input" :placeholder="t(action==='return-ack'?'replenishment.inboundNo':'replenishment.outboundNo')" :disabled="busy"/>
  <input v-if="hasSource" v-model="line" class="rp-input" :placeholder="t('replenishment.line')" :disabled="busy"/>
  <input v-if="hasMapping" v-model="item" class="rp-input" :placeholder="t('replenishment.item')" :disabled="busy"/>
  <input v-if="hasMapping" v-model="unit" class="rp-input" :placeholder="t('replenishment.unit')" :disabled="busy"/>
  <input v-if="hasMapping" v-model="factor" class="rp-input" :placeholder="t('replenishment.factor')" :disabled="busy"/>
  <input v-if="hasQuantity" v-model="quantity" class="rp-input" :placeholder="t('replenishment.cumulative')" :disabled="busy"/>
  <text v-if="hasQuantity" class="sh-hint">{{t('replenishment.cumulativeHint')}}</text>
  <input v-if="hasMovement" v-model="movement" class="rp-input" :placeholder="t('replenishment.movement')" :disabled="busy"/>
  <text v-if="hasMovement" class="sh-hint">{{t('replenishment.manualHint')}}</text>
  <input v-if="hasReason" v-model="reason" class="rp-input" :placeholder="t('replenishment.reason')" :disabled="busy"/>
  <button class="sh-btn" :disabled="busy" @tap="submit">{{t('replenishment.submit')}}</button>
  <button class="sh-btn sh-btn--soft" :disabled="busy||!no" @tap="reconcile">{{t('replenishment.reconcile')}}</button>
 </view>
 <view v-if="selected" class="sh-card rp-card"><text class="sh-num">{{selected.replenishmentNo}}</text><text>{{selected.itemNo}} · {{selected.baseUnit}}</text></view>
 <view v-if="checks" class="sh-card rp-card"><text class="txt-title">{{t('replenishment.reconciliation')}}</text><text v-if="!checks.issues.length">{{t('replenishment.matched')}}</text><text v-for="issue in checks.issues" :key="issue">{{t(`replenishment.issue.${issue}`)}}</text><text class="sh-hint">{{t('replenishment.readOnly')}}</text></view>
 <text v-if="error" class="sh-hint">{{error}}</text>
</sh-scaffold>
</template>
<style scoped>.rp-card{display:flex;flex-direction:column;gap:20rpx}.rp-input{min-height:76rpx;padding:16rpx;border:1rpx solid var(--sh-line);border-radius:12rpx}.rp-row{display:flex;flex-direction:column;gap:12rpx;padding:20rpx 0;border-bottom:1rpx solid var(--sh-line)}</style>
