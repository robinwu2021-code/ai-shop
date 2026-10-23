<script setup lang="ts">
import {computed,ref,watch} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {useMerchantStore} from '@/stores/merchant';
import {idempotencyKey} from '@shared/net/http-client';
import type {IssuerView,IssuerConfiguration,IssuerRoute,StoreIssuerOptions} from '@/api/stored-value-issuers';
import type {PaymentMerchant,ManagementGrant} from '@/api/management-types';
const {t}=useI18n();const merchant=useMerchantStore();
const type=ref<'STORE'|'BRAND'>('STORE'),no=ref(''),management=ref('');
const issuer=ref<IssuerView|null>(null),options=ref<StoreIssuerOptions|null>(null),entities=ref<Array<{entityNo:string;name:string;accepted:boolean;canAccept:boolean}>>([]),routes=ref<IssuerRoute[]>([]),candidates=ref<IssuerRoute[]>([]);
const name=ref(''),entity=ref(''),choosingEntity=ref(false),choosingRoute=ref(false),busy=ref(false),loading=ref(true),failed=ref(false),error=ref(''),canEdit=ref(false);
type Pending={configuration:IssuerConfiguration;key:string;issuerNo:string|null};
const canPrepare=ref(false),needsPrepare=ref(false);
const pending=ref<Pending|null>(null);let generation=0,originStore='';
const storage=computed(()=>`sv-issuer:${management.value}:${type.value}:${no.value}`);
const locked=computed(()=>busy.value||!!pending.value);
const entityName=computed(()=>entities.value.find(e=>e.entityNo===entity.value)?.name||entity.value);
const scopeLabel=computed(()=>t(type.value==='BRAND'?'issuer.brandScope':'issuer.storeScope'));
const status=computed(()=>issuer.value?.status||options.value?.issuers[0]?.status||(options.value?.legacyMode?'ACTIVE':'DRAFT'));
const valid=computed(()=>name.value.trim()&&entity.value&&routes.value.length&&entities.value.some(e=>e.entityNo===entity.value&&e.accepted));
function live(token:number){return token===generation&&(type.value==='BRAND'||!originStore||originStore===merchant.storeNo);}
function restore(configuration:IssuerConfiguration){name.value=configuration.name;entity.value=configuration.settlementEntityNo;routes.value=configuration.routes.map(r=>({...r}));}
async function load(){
 const token=++generation;loading.value=true;failed.value=false;error.value='';canEdit.value=false;
 try{
  if(!management.value){
   options.value=await api.mSvStoreIssuers(no.value);
   name.value=options.value.issuers[0]?.name||t('issuer.storeScope');
   return;
  }
  let result:{issuer:IssuerView|null};
  try{result=await api.mSvIssuer(type.value,no.value,management.value);}catch(e){if(canPrepare.value&&(e as {code?:number}).code===10403){needsPrepare.value=true;name.value=t('issuer.storeScope');return;}throw e;}
  if(!live(token))return;needsPrepare.value=false;
  issuer.value=result.issuer;
  if(result.issuer){const v=result.issuer;restore({...v,expectedRevision:v.revision});}
  else{name.value=t(type.value==='BRAND'?'issuer.brandScope':'issuer.storeScope');entity.value='';routes.value=[];}
  try{const list=await api.mSvIssuerEntities(management.value,type.value,no.value);if(!live(token))return;entities.value=list;canEdit.value=list.length>0;}catch{canEdit.value=false;}
  const saved=uni.getStorageSync(storage.value);
  if(saved&&canEdit.value){const p=JSON.parse(String(saved)) as Pending;if(p.key&&p.configuration){pending.value=p;restore(p.configuration);}}
 }catch(e){if(live(token)){failed.value=true;error.value=(e as Error).message;}}
 finally{if(token===generation)loading.value=false;}
}
async function prepareSettings(){
 if(busy.value||!canPrepare.value)return;busy.value=true;error.value='';
 try{await api.mSvPrepareIssuer(no.value,management.value);await load();}catch(e){error.value=(e as Error).message;}finally{busy.value=false;}
}
async function chooseEntity(value:{entityNo:string;name:string;accepted:boolean;canAccept:boolean}){
 if(locked.value)return;
 if(!value.accepted){
  if(!value.canAccept)return;busy.value=true;error.value='';
  try{
   const context=await api.mManagementContext();const grants:ManagementGrant[]=[];let cursor:string|undefined;
   do{const page=await api.mManagementResources(management.value,{view:'GRANTS',actorNo:context.actorKey,cursor});grants.push(...page.items);cursor=page.nextCursor||undefined;}while(cursor&&grants.length<1000);
   if(cursor)throw Error(t('management.retry'));
   const prior=grants.find(g=>g.actorNo===context.actorKey&&g.resourceType==='ENTITY'&&g.resourceNo===value.entityNo&&g.permissionCode==='sv.issuer.settlement.authorize');
   await api.mManagementGrant(management.value,{actorNo:context.actorKey,resourceType:'ENTITY',resourceNo:value.entityNo,permissionCode:'sv.issuer.settlement.authorize',delegatable:false,...(prior?{expectedRevision:prior.revision,reactivate:prior.status==='REVOKED'}:{})});
   value.accepted=true;value.canAccept=false;
  }catch(e){error.value=(e as Error).message;return;}finally{busy.value=false;}
 }
 entity.value=value.entityNo;routes.value=[{paymentMerchantNo:null,entityNo:value.entityNo,storeNo:type.value==='STORE'?no.value:null,channel:'MERCHANT_OFFLINE',scene:'OFFLINE'}];choosingEntity.value=false;
}
async function chooseRoutes(){
 if(locked.value||!entity.value)return;
 busy.value=true;error.value='';
 try{
  const token=generation;const list=await api.mPaymentMerchantCandidates(entity.value,management.value,type.value==='STORE'?{storeNo:no.value}:{});if(!live(token))return;
  candidates.value=list.flatMap((m:PaymentMerchant)=>m.payMethods.filter(scene=>['JSAPI','APP','H5','NATIVE','MICROPAY','PAYCODE'].includes(scene)).map(scene=>({paymentMerchantNo:m.payMerchantNo,entityNo:m.entityNo,storeNo:m.storeNo||null,channel:m.payChannel,scene})));
  choosingRoute.value=true;
 }catch(e){error.value=(e as Error).message;}finally{busy.value=false;}
}
function addRoute(route:IssuerRoute){if(locked.value)return;routes.value=routes.value.filter(r=>r.channel!==route.channel||r.scene!==route.scene||r.storeNo!==route.storeNo);routes.value.push({...route});choosingRoute.value=false;}
function removeRoute(index:number){if(!locked.value)routes.value.splice(index,1);}
function configuration():IssuerConfiguration{
 const old=issuer.value;
 return {name:name.value.trim(),settlementEntityNo:entity.value,collectionMode:old?.collectionMode||(type.value==='BRAND'?'CENTRALIZED':'STORE_LOCAL'),clearingMode:old?.clearingMode||'INTERNAL_RECON',debitAllocationMode:old?.debitAllocationMode||'LEGACY_SNAPSHOT',currency:old?.currency||'CNY',marketZone:old?.marketZone||'Asia/Shanghai',routes:routes.value.map(r=>({...r})),expectedRevision:old?.revision||0};
}
async function save(){
 if(busy.value||!canEdit.value||!live(generation)||!valid.value)return;busy.value=true;error.value='';
 const token=generation;
 try{
  if(!pending.value){
   const body=configuration();
   if(issuer.value){const impact=await api.mSvIssuerImpact(issuer.value.issuerNo,{managementNo:management.value,configuration:body});if(!live(token))return;if(!impact.allowed){error.value=impact.blockers.map(code=>t('issuer.blockers.'+code)).join(' · ');return;}}
   pending.value={configuration:body,key:idempotencyKey(),issuerNo:issuer.value?.issuerNo||null};uni.setStorageSync(storage.value,JSON.stringify(pending.value));
  }
  const p=pending.value;const body={managementNo:management.value,configuration:p.configuration};
  const result=p.issuerNo?await api.mSvIssuerVersion(p.issuerNo,body,p.key):await api.mSvConfigureIssuer(type.value,no.value,body,p.key);
  if(!live(token))return;issuer.value=result;pending.value=null;uni.removeStorageSync(storage.value);restore({...result,expectedRevision:result.revision});
 }catch(e){
  if(!live(token))return;error.value=(e as Error).message;
  const code=(e as {code?:number}).code;
  if(code&&[10400,10403,10404,10409,70006,40040].includes(code)){pending.value=null;uni.removeStorageSync(storage.value);}
 }finally{if(token===generation)busy.value=false;}
}
watch(()=>merchant.storeNo,()=>{if(originStore&&type.value==='STORE'&&originStore!==merchant.storeNo){generation++;failed.value=true;error.value=t('storedValue.leaveStore');canEdit.value=false;choosingEntity.value=false;choosingRoute.value=false;}});
onLoad(q=>{type.value=q?.b?'BRAND':'STORE';no.value=q?.b||q?.s||merchant.storeNo;management.value=q?.m||'';canPrepare.value=q?.prepare==='1';originStore=q?.local==='1'?merchant.storeNo:'';void load();});
</script>
<template><sh-scaffold title-key="issuer.title" :pending="loading" :failed="failed" @retry="load">
 <view v-if="!loading&&!failed" class="sh-card">
  <view class="sh-row sh-row--between"><text class="txt-title">{{scopeLabel}}</text><text class="sh-chip">{{t('issuer.states.'+status)}}</text></view>
  <text v-if="type==='BRAND'" class="sh-hint">{{t('issuer.brandDraftOnly')}}</text>
  <template v-if="canEdit">
   <view class="field"><text class="field__label">{{t('issuer.name')}}</text><input v-model="name" class="field__input" maxlength="64" :disabled="locked"/></view>
   <view data-testid="issuer-entity" role="button" class="sh-cell sh-row sh-row--between sh-hit" @tap="!locked&&(choosingEntity=true)"><text>{{t('issuer.settlementEntity')}}</text><view class="sh-row"><text>{{entityName||t('issuer.chooseEntity')}}</text><sh-go/></view></view>
  </template>
  <template v-else><sh-kv :label="t('issuer.name')">{{name}}</sh-kv><sh-kv v-if="issuer" :label="t('issuer.settlementEntity')">{{issuer.settlementEntityNo}}</sh-kv><text v-if="options&&!options.legacyMode&&options.issuers[0]?.unavailableReason" class="sh-hint">{{t('issuer.blockers.'+options.issuers[0].unavailableReason)}}</text></template>
 </view>
 <view v-if="management&&!loading&&!failed" class="sh-card">
  <view class="sh-row sh-row--between"><text class="txt-strong">{{t('issuer.routes')}}</text><view v-if="canEdit&&entity" role="button" class="sh-link sh-hit" @tap="chooseRoutes">{{t('issuer.addRoute')}}</view></view>
  <view v-for="(route,index) in routes" :key="route.channel+route.scene+route.storeNo" class="sh-cell sh-row sh-row--between"><view><text>{{t('issuer.channels.'+route.channel)}}</text><view v-if="route.paymentMerchantNo" class="sh-muted txt-sub">{{route.paymentMerchantNo}} · {{route.scene}}</view></view><view v-if="canEdit&&!locked" role="button" class="sh-link sh-hit" @tap="removeRoute(index)">{{t('issuer.remove')}}</view></view>
  <sh-empty v-if="!routes.length" compact :text="t('issuer.noRoutes')"/>
 </view>
 <text v-if="error" class="sh-hint">{{error}}</text><text v-if="pending" class="sh-hint">{{t('issuer.pendingSave')}}</text>
 <sh-actionbar v-if="needsPrepare&&!failed"><view class="sh-row"><view data-testid="issuer-prepare" role="button" class="sh-btn sh-fill" @tap="prepareSettings">{{t(busy?'management.loading':'issuer.prepareSettings')}}</view></view></sh-actionbar>
 <sh-actionbar v-else-if="canEdit&&!failed"><view class="sh-row"><view data-testid="issuer-save" role="button" class="sh-btn sh-fill" :class="{'is-disabled':busy||!valid}" :aria-disabled="busy||!valid" @tap="save">{{t(busy?'management.loading':pending?'issuer.retrySave':'management.save')}}</view></view></sh-actionbar>
 <sh-sheet :visible="choosingEntity" :title="t('issuer.chooseEntity')" @close="choosingEntity=false"><view data-testid="issuer-entity-choice" v-for="item in entities" :key="item.entityNo" role="button" class="sh-cell sh-row sh-row--between sh-hit" @tap="chooseEntity(item)"><text>{{item.name}}</text><view class="sh-row"><text class="sh-muted">{{t(item.accepted?'issuer.accepted':'issuer.acceptAndChoose')}}</text><sh-go/></view></view></sh-sheet>
 <sh-sheet :visible="choosingRoute" :title="t('issuer.addRoute')" @close="choosingRoute=false"><view v-for="route in candidates" :key="route.paymentMerchantNo+route.channel+route.scene" role="button" class="sh-cell sh-hit" @tap="addRoute(route)"><text>{{t('issuer.channels.'+route.channel)}} · {{route.scene}}</text><view class="sh-muted txt-sub">{{route.paymentMerchantNo}}</view></view><sh-empty v-if="!candidates.length" compact :text="t('issuer.noRoutes')"/></sh-sheet>
</sh-scaffold></template>
