<script setup lang="ts">
import {ref,watch} from 'vue';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {idempotencyKey} from '@shared/net/http-client';
import type {Brand,BrandImpact,BrandChange} from '@/api/management-types';
const props=defineProps<{visible:boolean;managementNo:string;storeNo:string;currentBrandNo?:string}>();
const emit=defineEmits<{close:[];changed:[];pending:[changeNo:string]}>();
const {t}=useI18n();const brands=ref<Brand[]>([]);const next=ref<string|null>(null);const selected=ref<string|null|undefined>();const impact=ref<BrandImpact|null>(null);const busy=ref(false);const loading=ref(false);const failed=ref(false);const pending=ref<BrandChange|null>(null);let generation=0;let key='';let decisionKey='';
watch(()=>[props.visible,props.storeNo,props.managementNo],async()=>{const run=++generation;selected.value=undefined;impact.value=null;pending.value=null;brands.value=[];next.value=null;busy.value=false;failed.value=false;key='';decisionKey='';if(!props.visible)return;loading.value=true;try{const page=await api.mBrands(props.managementNo,{status:'ACTIVE'});if(run===generation){brands.value=page.items;next.value=page.nextCursor}}catch{if(run===generation)failed.value=true}finally{if(run===generation)loading.value=false}},{immediate:true});
async function more(){if(!next.value||loading.value)return;const run=generation;loading.value=true;try{const page=await api.mBrands(props.managementNo,{status:'ACTIVE',cursor:next.value});if(run===generation){brands.value.push(...page.items);next.value=page.nextCursor}}catch{if(run===generation)failed.value=true}finally{if(run===generation)loading.value=false}}
async function choose(brandNo:string|null){if(busy.value)return;const run=++generation;selected.value=brandNo;impact.value=null;pending.value=null;key=idempotencyKey();decisionKey=idempotencyKey();loading.value=true;failed.value=false;try{const check=await api.mCheckBrandChange(props.storeNo,{managementNo:props.managementNo,toBrandNo:brandNo});if(run===generation)impact.value=check}catch{if(run===generation)failed.value=true}finally{if(run===generation)loading.value=false}}
async function confirm(){if(busy.value||!impact.value?.allowed||selected.value===undefined)return;const run=generation;busy.value=true;failed.value=false;try{
 let change=pending.value;
 if(change)change=await api.mBrandChange(change.changeNo);
 else change=await api.mSubmitBrandChange(props.storeNo,{managementNo:props.managementNo,toBrandNo:selected.value,checkToken:impact.value.checkToken,expectedStoreRevision:impact.value.storeVersion,expectedScopeRevision:impact.value.bindingRevision,expectedFromBrandRevision:impact.value.fromRevision,expectedToBrandRevision:impact.value.toRevision,reason:t('management.configureBrand')},key);
 if(run!==generation)return;pending.value=change;
 if(change.status==='CHECKED'&&impact.value.canDecide)change=await api.mDecideBrandChange(change.changeNo,{decision:'APPROVE',expectedRevision:change.revision,reason:t('management.configureBrand')},decisionKey);
 if(run!==generation)return;pending.value=change;
 if(change.status==='COMPLETED'){emit('changed');emit('close')}else emit('pending',change.changeNo);
 }catch{if(run===generation)failed.value=true}finally{if(run===generation)busy.value=false}}
</script>
<template><sh-sheet :visible="visible" :title="t('management.configureBrand')" @close="emit('close')">
 <view v-if="loading" class="txt-sub sh-muted">{{t('management.loading')}}</view>
 <view v-for="brand in brands" :key="brand.brandNo" class="sh-cell sh-row sh-row--between" data-testid="brand-option" @tap="choose(brand.brandNo)"><text>{{brand.name}}</text><sh-check :model-value="selected===brand.brandNo" round/></view>
 <view v-if="currentBrandNo" class="sh-cell" @tap="choose(null)">{{t('management.leaveBrand')}}</view>
 <view v-if="next" class="sh-btn sh-btn--soft" @tap="more">{{t('management.more')}}</view>
 <sh-empty v-if="!loading&&!brands.length" bare :text="t('management.empty')"/>
 <view v-if="impact&&!impact.allowed" class="txt-sub sh-muted">{{t('management.blocked')}}<text v-for="reason in impact.blockers" :key="reason"> · {{t('management.blocker.'+reason)}}</text></view>
 <view v-if="failed" class="txt-sub sh-muted">{{t('management.retry')}}</view>
 <view v-if="pending&&pending.status!=='COMPLETED'" class="txt-sub">{{t('management.state.'+pending.status)}}</view>
 <template #foot><view class="sh-btn" :class="{'sh-btn--muted':busy||!impact?.allowed}" data-testid="brand-confirm" @tap="confirm">{{t(busy?'management.loading':'management.confirm')}}</view></template>
</sh-sheet></template>
