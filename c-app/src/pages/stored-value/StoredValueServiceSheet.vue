<script setup lang="ts">
import {ref,watch} from 'vue';
import {useI18n} from 'vue-i18n';
import {api} from '@/api';
import {money} from '@shared/utils/format';
import type {StoredValueService} from '@/api/stored-value-types';
const props=defineProps<{orderNo:string}>();
const emit=defineEmits<{close:[];confirmed:[]}>();
const {t}=useI18n();const service=ref<StoredValueService|null>(null),busy=ref(false),loading=ref(false),error=ref('');let generation=0;
async function load(){const token=++generation;loading.value=true;error.value='';service.value=null;try{const value=await api.storedValueService(props.orderNo);if(token===generation)service.value=value;}catch(e){if(token===generation)error.value=(e as Error).message;}finally{if(token===generation)loading.value=false;}}
async function confirm(){
 const s=service.value;if(!s?.confirmation||s.status!=='WAIT_AUTH'||busy.value)return;
 busy.value=true;error.value='';const token=generation;
 try{const q=s.confirmation;await api.storedValueAuthorize(s.serviceOrderNo,{orderRevision:q.orderRevision,quoteRef:q.quoteRef,selectionDigest:q.selectionDigest,requestKey:'SV_CONFIRM_'+q.quoteRef});if(token===generation){await load();emit('confirmed');}}
 catch(e){if(token===generation)error.value=(e as Error).message;}finally{busy.value=false;}
}
watch(()=>props.orderNo,()=>{if(props.orderNo)void load();else{generation++;service.value=null;}},{immediate:true});
</script>
<template><sh-sheet :visible="!!orderNo" :title="t('storedValue.confirmConsumption')" @close="!busy&&emit('close')">
 <sh-empty v-if="loading" pending bare/>
 <view v-if="service" class="sv-confirm">
  <text class="txt-title">{{service.storeName}}</text>
  <sh-kv v-for="(item,i) in service.items" :key="i" :label="item.title" between>× {{item.quantity}}</sh-kv>
  <template v-if="service.payment">
   <sh-kv :label="t('storedValue.paymentWallet')" between>{{t(service.payment.scopeType==='BRAND'?'storedValue.brandScope':'storedValue.storeScope')}}</sh-kv>
   <text v-if="service.payment.issuerName" class="txt-caption sh-muted">{{service.payment.issuerName}}</text>
   <sh-kv :label="t('storedValue.consumeAmount')" between>{{money(service.payment.amountMinor,service.payment.currency)}}</sh-kv>
  </template>
  <text v-if="service.status!=='WAIT_AUTH'" class="sh-hint">{{t('storedValue.serviceState.'+service.status)}}</text>
 </view>
 <text v-if="error" class="sh-hint">{{error}}</text>
 <template #foot><view v-if="service?.status==='WAIT_AUTH'&&service.confirmation" role="button" class="sh-btn" :class="{'is-disabled':busy||loading}" @tap="confirm">{{t('storedValue.confirmConsumption')}}</view><view v-else role="button" class="sh-btn sh-btn--muted" @tap="load">{{t('storedValue.refreshConfirmation')}}</view></template>
</sh-sheet></template>
<style scoped>.sv-confirm{display:flex;flex-direction:column;gap:var(--sh-gap-block);min-height:24vh}</style>
