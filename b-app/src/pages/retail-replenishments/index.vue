<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {onShow} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {idempotencyKey} from '@shared/net/http-client';
import {resendRrCommand,restoreRrCommand,rrCount,rrFactor,rrStorageKey,sameRrScope} from '@/shared/retail-replenishment-command';
import type {RrAdjustment,RrCommand,RrContext,RrPage,RrReconciliation,RrScope,RrTarget,RrView} from '@/api/retail-replenishment';
const {t}=useI18n(),merchant=useMerchantStore();
const context=ref<RrContext|null>(null),page=ref<RrPage|null>(null),checks=ref<RrReconciliation|null>(null),adjusted=ref<RrAdjustment|null>(null);
const pending=ref<RrCommand[]>([]),busy=ref(false),loading=ref(false),failed=ref(false),error=ref(''),selected=ref<RrView|null>(null);
let generation=0,account='';
const actions=['register','receive','close','return-dispatch','return-ack','adjust','manual-receipt'] as const;
const action=ref<typeof actions[number]>('register');const actionLabels=computed(()=>actions.map(k=>t(`retailReplenishment.action.${k}`)));
const no=ref(''),documentNo=ref(''),line=ref('1'),item=ref(''),unit=ref(''),factor=ref('1'),quantity=ref(''),reason=ref(''),movement=ref('');
const needsOriginal=computed(()=>!['register','adjust'].includes(action.value));
const hasSource=computed(()=>action.value==='register'||action.value==='return-ack');
const hasItem=computed(()=>action.value==='register'||action.value==='adjust');
const hasQuantity=computed(()=>['receive','return-dispatch','adjust'].includes(action.value));
const hasReason=computed(()=>['close','return-dispatch','adjust'].includes(action.value));
function scope():RrScope|null{return context.value&&context.value.storeNo===merchant.storeNo&&account===merchant.profile?.merchantNo?context.value:null;}
function message(e:unknown){const key=(e as Error).message;return key.startsWith('retailReplenishment.')?t(key):key;}
const pool=(target:RrTarget)=>t(target.quantityScope==='STORE'?'retailReplenishment.storePool':'retailReplenishment.entityPool',{ref:target.poolRef});
function readPending(s:RrScope){try{const rows=JSON.parse(String(uni.getStorageSync(rrStorageKey(s))||'[]'));return Array.isArray(rows)?rows.map(v=>restoreRrCommand(JSON.stringify(v),s)).filter((v):v is RrCommand=>!!v):[];}catch{return [];}}
function persist(s:RrScope,rows:RrCommand[]){if(rows.length)uni.setStorageSync(rrStorageKey(s),JSON.stringify(rows));else uni.removeStorageSync(rrStorageKey(s));if(sameRrScope(s,scope()))pending.value=rows;}
async function load(){
 const token=++generation,store=merchant.storeNo,actor=merchant.profile?.merchantNo;loading.value=true;error.value='';
 try{const c=await api.mRrContext();if(token!==generation||store!==merchant.storeNo||actor!==merchant.profile?.merchantNo)return;
  if(c.storeNo!==store)throw Error('retailReplenishment.scopeChanged');context.value=c;account=actor||'';pending.value=readPending(c);
  const rows=await api.mRrList(c,{size:20});if(token!==generation||!sameRrScope(c,scope()))return;page.value=rows;failed.value=false;
 }catch(e){if(token===generation){failed.value=true;error.value=message(e);}}finally{if(token===generation)loading.value=false;}
}
async function more(){const s=scope(),after=page.value?.nextCursor;if(!s||!after||loading.value)return;loading.value=true;
 try{const rows=await api.mRrList(s,{after,size:20});if(sameRrScope(s,scope()))page.value={nextCursor:rows.nextCursor,items:[...(page.value?.items||[]),...rows.items]};}
 catch(e){if(sameRrScope(s,scope()))error.value=message(e);}finally{if(sameRrScope(s,scope()))loading.value=false;}
}
function select(row:RrView){selected.value=row;no.value=row.original.originalNo;item.value=row.original.mapping.itemNo;unit.value=row.original.mapping.baseUnit;quantity.value='';checks.value=null;}
function required(v:string){if(!v.trim())throw Error('retailReplenishment.required');return v.trim();}
function origin(){const n=Number(line.value);if(!Number.isInteger(n)||n<1||n>2147483647)throw Error('retailReplenishment.invalidLine');return {documentNo:required(documentNo.value),lineNo:n};}
function operation():RrCommand {
 const s=scope();if(!s)throw Error('retailReplenishment.scopeChanged');const base={scope:s,key:idempotencyKey()};
 switch(action.value){
  case 'register':return {...base,kind:'register',body:{...origin(),mapping:{itemNo:required(item.value),baseUnit:required(unit.value),factor:rrFactor(factor.value)}}};
  case 'adjust':return {...base,kind:'adjust',body:{itemNo:required(item.value),baseUnit:required(unit.value),delta:rrCount(quantity.value,true),reason:required(reason.value)}};
  case 'receive':return {...base,kind:'receive',no:required(no.value),body:{cumulativeQuantity:rrCount(quantity.value)}};
  case 'close':return {...base,kind:'close',no:required(no.value),body:{reason:required(reason.value)}};
  case 'return-dispatch':return {...base,kind:'return-dispatch',no:required(no.value),body:{cumulativeQuantity:rrCount(quantity.value),reason:required(reason.value)}};
  case 'return-ack':return {...base,kind:'return-ack',no:required(no.value),body:origin()};
  case 'manual-receipt':return {...base,kind:'manual-receipt',no:required(no.value),body:{movementNo:required(movement.value)}};
 }
}
const intent=(c:RrCommand)=>c.kind==='configure'?c.kind:'no' in c?`${c.kind}:${c.no}`:c.kind==='adjust'?`${c.kind}:${c.body.itemNo}`:`${c.kind}:${c.body.documentNo}:${c.body.lineNo}`;
async function run(op:RrCommand){
 if(busy.value||!sameRrScope(op.scope,scope()))return;busy.value=true;error.value='';
 try{
  if(!op.attempted){op.attempted=true;persist(op.scope,readPending(op.scope).map(p=>p.key===op.key?op:p));}
  const result=await resendRrCommand(api,op,scope);
  persist(op.scope,readPending(op.scope).filter(p=>p.key!==op.key));
  if(sameRrScope(op.scope,scope())){
   if(result&&'movementNo' in result){adjusted.value=result;movement.value=result.movementNo;}
   if(result&&'original' in result)select(result);
   await load();
  }
 }catch(e){
  // A definite rejection was evaluated by the server and committed nothing; unknown outcomes keep the original key/body.
  const status=(e as {httpStatus?:number}).httpStatus,code=(e as {code?:number}).code;
  if((status&&[400,403,404,409].includes(status))||(code&&[10400,10403,10404,10409].includes(code))){persist(op.scope,readPending(op.scope).filter(p=>p.key!==op.key));if(sameRrScope(op.scope,scope()))await load();}
  if(sameRrScope(op.scope,scope()))error.value=message(e);
 }finally{busy.value=false;}
}
function enqueue(op:RrCommand){const all=readPending(op.scope);if(all.some(p=>intent(p)===intent(op)))throw Error('retailReplenishment.pendingFirst');persist(op.scope,[...all,op]);void run(op);}
function submit(){try{enqueue(operation());}catch(e){error.value=message(e);}}
function configure(enabled:boolean){try{const s=scope(),c=context.value?.connection;if(!s||!c)throw Error('retailReplenishment.scopeChanged');enqueue({scope:s,key:idempotencyKey(),kind:'configure',body:{enabled,expectedVersion:c.revision,reason:required(reason.value)}});}catch(e){error.value=message(e);}}
async function reconcile(){const s=scope();if(!s||!no.value||busy.value)return;busy.value=true;error.value='';try{const result=await api.mRrReconciliation(s,no.value);if(sameRrScope(s,scope()))checks.value=result;}catch(e){if(sameRrScope(s,scope()))error.value=message(e);}finally{busy.value=false;}}
watch(()=>[merchant.storeNo,merchant.profile?.merchantNo],()=>{generation++;context.value=null;page.value=null;selected.value=null;checks.value=null;adjusted.value=null;pending.value=[];loading.value=false;error.value='';no.value='';});
onShow(async()=>{await merchant.ensureStores();await merchant.ensureScope();await load();});
</script>
<template>
<sh-scaffold title-key="retailReplenishment.title" :title-suffix="merchant.currentStore?.name" :denied="!merchant.can('biz:stock')" :failed="failed" :pending="!context&&!failed" @retry="load">
 <view v-if="context" class="sh-card rr-card">
  <text class="txt-title">{{t(context.connection.enabled?'retailReplenishment.connected':'retailReplenishment.disconnected')}}</text>
  <text class="sh-hint">{{t('retailReplenishment.physicalOnly')}}</text>
  <text class="sh-hint">{{t('retailReplenishment.localUnchanged')}}</text>
  <button class="sh-btn sh-btn--soft" :disabled="busy||loading" @tap="load">{{t('retailReplenishment.refresh')}}</button>
  <input v-model="reason" class="rr-input" :placeholder="t('retailReplenishment.reason')" :disabled="busy"/>
  <button class="sh-btn sh-btn--soft" :disabled="busy" @tap="configure(!context.connection.enabled)">{{t(context.connection.enabled?'retailReplenishment.disable':'retailReplenishment.enable')}}</button>
 </view>
 <view v-if="pending.length" class="sh-card rr-card">
  <text class="txt-title">{{t('retailReplenishment.pending')}}</text><text class="sh-hint">{{t('retailReplenishment.pendingHint')}}</text>
  <view v-for="p in pending" :key="p.key" class="rr-row"><text>{{t(`retailReplenishment.action.${p.kind}`)}} {{'no' in p?p.no:''}}</text><button class="sh-btn sh-btn--soft" :disabled="busy" @tap="run(p)">{{t('retailReplenishment.resume')}}</button></view>
 </view>
 <view v-if="page" class="sh-card rr-card">
  <text class="txt-title">{{t('retailReplenishment.attention')}}</text><text v-if="!page.items.length" class="sh-hint">{{t('retailReplenishment.empty')}}</text>
  <view v-for="row in page.items" :key="row.original.originalNo" class="rr-row" @tap="select(row)">
   <view><text class="sh-num">{{row.original.source.documentNo}} / {{row.original.source.lineNo}}</text><text> {{t(`retailReplenishment.state.${row.original.status}`)}}</text></view>
   <text>{{row.original.mapping.itemNo}} · {{pool(row.original.target)}}</text>
   <text>{{t('retailReplenishment.received')}} {{row.original.receivedQuantity}} / {{row.original.expectedQuantity}} {{row.original.mapping.baseUnit}}</text>
   <text>{{t('retailReplenishment.difference')}} {{row.differenceQuantity}} · {{t('retailReplenishment.returned')}} {{row.returnedQuantity}} / {{row.returnAcceptedQuantity}}</text>
  </view>
  <button class="sh-btn sh-btn--soft" v-if="page.nextCursor" :disabled="loading||busy" @tap="more">{{t('retailReplenishment.more')}}</button>
 </view>
 <view v-if="context" class="sh-card rr-card">
  <picker :range="actionLabels" @change="action=actions[Number($event.detail.value)]||'register'"><view class="rr-input">{{t(`retailReplenishment.action.${action}`)}}</view></picker>
  <input v-if="needsOriginal" v-model="no" class="rr-input" :placeholder="t('retailReplenishment.originalNo')" :disabled="busy"/>
  <input v-if="hasSource" v-model="documentNo" class="rr-input" :placeholder="t(action==='return-ack'?'retailReplenishment.inboundNo':'retailReplenishment.outboundNo')" :disabled="busy"/>
  <input v-if="hasSource" v-model="line" class="rr-input" :placeholder="t('retailReplenishment.line')" :disabled="busy"/>
  <input v-if="hasItem" v-model="item" class="rr-input" :placeholder="t('retailReplenishment.item')" :disabled="busy"/>
  <input v-if="hasItem" v-model="unit" class="rr-input" :placeholder="t('retailReplenishment.unit')" :disabled="busy"/>
  <input v-if="action==='register'" v-model="factor" class="rr-input" :placeholder="t('retailReplenishment.factor')" :disabled="busy"/>
  <input v-if="hasQuantity" v-model="quantity" class="rr-input" :placeholder="t(action==='adjust'?'retailReplenishment.delta':'retailReplenishment.cumulative')" :disabled="busy"/>
  <text v-if="hasQuantity" class="sh-hint">{{t(action==='adjust'?'retailReplenishment.adjustHint':'retailReplenishment.cumulativeHint')}}</text>
  <input v-if="action==='manual-receipt'" v-model="movement" class="rr-input" :placeholder="t('retailReplenishment.movement')" :disabled="busy"/>
  <text v-if="action==='manual-receipt'" class="sh-hint">{{t('retailReplenishment.manualHint')}}</text>
  <input v-if="hasReason" v-model="reason" class="rr-input" :placeholder="t('retailReplenishment.reason')" :disabled="busy"/>
  <button class="sh-btn" :disabled="busy" @tap="submit">{{t('retailReplenishment.submit')}}</button>
  <button class="sh-btn sh-btn--soft" :disabled="busy||!no" @tap="reconcile">{{t('retailReplenishment.reconcile')}}</button>
 </view>
 <view v-if="adjusted" class="sh-card rr-card"><text class="txt-title">{{t('retailReplenishment.adjusted')}}</text><text class="sh-num">{{adjusted.movementNo}}</text><text>{{pool(adjusted.target)}} · {{adjusted.onHandBefore}} → {{adjusted.onHandAfter}}</text></view>
 <view v-if="selected" class="sh-card rr-card"><text class="sh-num">{{selected.original.originalNo}}</text><text>{{selected.original.mapping.itemNo}} · {{pool(selected.original.target)}}</text></view>
 <view v-if="checks" class="sh-card rr-card"><text class="txt-title">{{t('retailReplenishment.reconciliation')}}</text><text v-if="!checks.issues.length">{{t('retailReplenishment.matched')}}</text><text v-for="issue in checks.issues" :key="issue">{{t(`retailReplenishment.issue.${issue}`)}}</text><text class="sh-hint">{{t('retailReplenishment.readOnly')}}</text></view>
 <text v-if="error" class="sh-hint">{{error}}</text>
</sh-scaffold>
</template>
<style scoped>.rr-card{display:flex;flex-direction:column;gap:20rpx}.rr-input{min-height:76rpx;padding:16rpx;border:1rpx solid var(--sh-line);border-radius:12rpx}.rr-row{display:flex;flex-direction:column;gap:12rpx;padding:20rpx 0;border-bottom:1rpx solid var(--sh-line)}</style>
