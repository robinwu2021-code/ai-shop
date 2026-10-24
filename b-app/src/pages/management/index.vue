<script setup lang="ts">
import {computed,ref} from 'vue';import {onShow} from '@dcloudio/uni-app';import {useI18n} from 'vue-i18n';import {api} from '@/api';import {managementGo} from '@/shared/management';import {idempotencyKey} from '@shared/net/http-client';import type {ManagementContext,ManagementSpace} from '@/api/management-types';
const governance=ref(false);
const {t}=useI18n();const context=ref<ManagementContext|null>(null);const current=ref<ManagementSpace|null>(null);const name=ref('');const busy=ref(false);const failed=ref(false);const choosing=ref(false);const sources=ref(false);let createKey=idempotencyKey();
const canManage=computed(()=>current.value?.actions.includes('management.grant.manage'));
async function load(){failed.value=false;try{governance.value=(await (api.mSvGovernanceCapability?.()||Promise.resolve({enabled:false})).catch(()=>({enabled:false}))).enabled;context.value=await api.mManagementContext();const saved=String(uni.getStorageSync('management:'+context.value.actorKey)||'');current.value=context.value.spaces.find(s=>s.managementNo===saved)||context.value.spaces[0]||null}catch{failed.value=true}}
function select(space:ManagementSpace){current.value=space;choosing.value=false;uni.setStorageSync('management:'+context.value!.actorKey,space.managementNo)}
async function create(accountNo:string){if(busy.value||!name.value.trim())return;busy.value=true;failed.value=false;try{const result=await api.mCreateManagementSpace({name:name.value.trim(),sourceAccountNo:accountNo},createKey);uni.setStorageSync('management:'+context.value!.actorKey,result.managementNo);sources.value=false;await load();createKey=idempotencyKey()}catch{failed.value=true}finally{busy.value=false}}
async function importSource(source:ManagementContext['bootstrapSources'][number]){if(busy.value||!current.value||!context.value)return;busy.value=true;failed.value=false;try{
 const m=current.value.managementNo,actor=context.value.actorKey;const add=(resourceType:string,resourceNo:string,permissionCode:string)=>api.mManagementGrant(m,{actorNo:actor,resourceType,resourceNo,permissionCode,delegatable:true});
 for(const permission of ['entity.read','payment.merchant.read'])await add('ENTITY',source.entityNo,permission);
 for(const store of source.stores)for(const permission of ['store.read','store.brand.change','sv.policy.read','sv.policy.manage','sv.store.activity.read','sv.store.activity.manage','sv.store.activity.publish'])await add('STORE',store.storeNo,permission);
 // Existing owner-checked payment endpoint supplies only the selected source entity's merchant identities.
 for(const merchant of source.paymentMerchants)if(merchant.payMerchantNo)await add('PAYMENT_MERCHANT',merchant.payMerchantNo,'payment.merchant.read');
 sources.value=false;await load();
 }catch{failed.value=true}finally{busy.value=false}}
onShow(load);
</script>
<template><sh-scaffold title-key="management.title">
 <view v-if="failed" class="sh-card sh-row sh-row--between"><text>{{t('management.retry')}}</text><view class="sh-btn sh-btn--soft" @tap="load">{{t('management.refresh')}}</view></view>
 <template v-if="context">
 <view v-if="current" class="sh-card sh-row sh-row--between" @tap="choosing=true"><text class="txt-title">{{current.name}}</text><sh-go/></view>
 <view v-if="current" class="sh-card">
  <view class="sh-cell sh-row sh-row--between" @tap="managementGo('brand_list',{m:current.managementNo})"><text>{{t('management.brands')}}</text><sh-go/></view>
  <view class="sh-cell sh-row sh-row--between" @tap="managementGo('management_resources',{m:current.managementNo,type:'ENTITY'})"><text>{{t('management.entities')}}</text><sh-go/></view>
  <view class="sh-cell sh-row sh-row--between" @tap="managementGo('management_resources',{m:current.managementNo,type:'PAYMENT_MERCHANT'})"><text>{{t('management.paymentMerchants')}}</text><sh-go/></view>
  <view class="sh-cell sh-row sh-row--between" @tap="managementGo('management_resources',{m:current.managementNo,type:'STORE'})"><text>{{t('management.stores')}}</text><sh-go/></view>
  <view v-if="governance&&current.actions.includes('sv.policy.read')" class="sh-cell sh-row sh-row--between" @tap="managementGo('storedValuePolicy',{m:current.managementNo})"><text>{{t('svGov.policies')}}</text><sh-go/></view>
  <view v-if="governance&&current.actions.includes('sv.store.activity.approve')" class="sh-cell sh-row sh-row--between" @tap="managementGo('storedValueApproval',{m:current.managementNo})"><text>{{t('svGov.approvals')}}</text><sh-go/></view>
  <view v-if="canManage" class="sh-cell sh-row sh-row--between" @tap="managementGo('management_grants',{m:current.managementNo})"><text>{{t('management.grants')}}</text><sh-go/></view>
 </view>
 <view v-if="current&&context.canBootstrap" class="sh-card"><view class="sh-btn sh-btn--soft" @tap="sources=true">{{t('management.importResources')}}</view></view>
 <view v-if="!current&&context.canBootstrap" class="sh-card"><view class="field"><text class="field__label">{{t('management.spaceName')}}</text><input v-model="name" class="field__input" maxlength="64"/></view><view class="sh-btn" @tap="sources=true">{{t('management.createSpace')}}</view></view>
 <sh-empty :pending="!context" v-if="!current&&!context.canBootstrap" :text="t('management.noAccess')"/>
 <sh-sheet :visible="choosing" :title="t('management.chooseSpace')" @close="choosing=false"><view v-for="space in context.spaces" :key="space.managementNo" class="sh-cell sh-row sh-row--between" @tap="select(space)"><text>{{space.name}}</text><sh-check :model-value="current?.managementNo===space.managementNo" round/></view></sh-sheet>
 <sh-sheet :visible="sources" :title="t('management.chooseEntity')" @close="sources=false"><view v-for="source in context.bootstrapSources" :key="source.accountNo" class="sh-cell" @tap="current?importSource(source):create(source.accountNo)">{{source.entityName||t('management.ownedEntity')}}</view><view v-if="busy" class="txt-sub sh-muted">{{t('management.loading')}}</view></sh-sheet>
 </template></sh-scaffold></template>
