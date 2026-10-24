<script setup lang="ts">
import { ref,watch } from 'vue';import { onLoad,onShow } from '@dcloudio/uni-app';import { useI18n } from 'vue-i18n';import { confirm } from '@ai-shop/ui/prompt';import { api } from '@/api';import { useMerchantStore } from '@/stores/merchant';import { money } from '@shared/utils/money';import { datetime } from '@shared/utils/datetime';import { idempotencyKey } from '@shared/net/http-client';import type { SvPlan } from '@/api/stored-value-types';
const governance=ref(false);
const {t}=useI18n();const merchant=useMerchantStore();const plan=ref<SvPlan|null>(null);const error=ref('');const failed=ref(false);const busy=ref(false);let no='',store='',seq=0;let command:{body:{storeNo:string;status:string;expectedRevision:number};key:string}|null=null;
async function load(){const token=++seq;try{governance.value=(await (api.mSvGovernanceCapability?.()||Promise.resolve({enabled:false})).catch(()=>({enabled:false}))).enabled;if(!store){await merchant.ensureStores();store=merchant.storeNo;}const value=await api.mSvPlan(no,store);if(token===seq&&store===merchant.storeNo){plan.value=value;failed.value=false;}}catch(e){if(token===seq){failed.value=true;error.value=(e as Error).message;}}}
async function status(){if(!plan.value||busy.value||store!==merchant.storeNo)return;if(!command&&!await confirm({title:t(plan.value.status==='ACTIVE'?'storedValue.stop':'storedValue.enable'),hint:t(plan.value.status==='ACTIVE'?'storedValue.stopConfirm':'storedValue.enableConfirm')}))return;if(store!==merchant.storeNo)return;busy.value=true;error.value='';try{command??={body:{storeNo:store,status:plan.value.status==='ACTIVE'?'INACTIVE':'ACTIVE',expectedRevision:plan.value.revision},key:idempotencyKey()};await api.mSvPlanStatus(no,command.body,command.key);command=null;await load();}catch(e){error.value=(e as Error).message;const code=(e as {code?:number}).code;if(code&&[10400,10403,10404,10409,70006,40040].includes(code)){command=null;await load();}}finally{busy.value=false;}}
watch(()=>merchant.storeNo,()=>{seq++;plan.value=null;failed.value=true;error.value=t('storedValue.leaveStore');});onLoad(q=>{no=q?.planNo||'';void load();});onShow(()=>{if(store&&no)void load();});
function navigate(url:string){uni.navigateTo({url});}
</script>
<template>
 <sh-scaffold :title-suffix="merchant.multiStore ? merchant.currentStore?.name : ''" title-key="storedValue.planDetail" :denied="!merchant.can('pay:sv:plan:read')&&!merchant.can('pay:sv:plan:manage')&&!merchant.can('pay:sv:recharge:create')" :failed="failed" :pending="!plan&&!failed" @retry="load">
  <template v-if="plan">
   <view class="sh-card sv-card">
    <view class="sh-row sh-row--between">
     <text class="txt-title sh-fill">{{plan.name}}</text>
     <text class="sh-chip" :class="plan.status==='ACTIVE'?'sh-chip--success':''">{{t(plan.status==='ACTIVE'?'storedValue.active':'storedValue.inactive')}}</text>
    </view>
    <sh-kv :label="t('storedValue.paid')" between>{{money(plan.paidAmountMinor,plan.currency)}}</sh-kv>
    <sh-kv :label="t('storedValue.bonus')" between>{{money(plan.bonusAmountMinor,plan.currency)}}</sh-kv>
    <sh-kv :label="t('storedValue.total')" between><text class="txt-price sh-num">{{money(plan.totalAmountMinor,plan.currency)}}</text></sh-kv>
   </view>
   <view class="sh-card sv-card">
    <sh-kv :label="t('storedValue.createdAt')" between><text class="sh-num">{{datetime(Date.parse(plan.createdAt))}}</text></sh-kv>
    <sh-kv :label="t('storedValue.updatedAt')" between><text class="sh-num">{{plan.updatedAt?datetime(Date.parse(plan.updatedAt)):'—'}}</text></sh-kv>
   </view>
   <view v-if="!governance&&merchant.can('pay:sv:plan:manage')" data-testid="plan-management">
    <view role="button" class="sh-btn" :class="[plan.status==='ACTIVE'?'sh-btn--danger':'sh-btn--soft',{'is-disabled':busy||store!==merchant.storeNo}]" :aria-disabled="busy||store!==merchant.storeNo" @tap="status">
     {{t(busy?'storedValue.loading':plan.status==='ACTIVE'?'storedValue.stop':'storedValue.enable')}}
    </view>
   </view>
  </template>
  <text v-if="error" class="sh-hint">{{error}}</text>
  <sh-actionbar v-if="plan?.status==='ACTIVE'&&merchant.can('pay:sv:recharge:create')">
   <view role="button" class="sh-btn" @tap="navigate(`/pages/stored-value/index?tab=customers&planNo=${encodeURIComponent(no)}`)">{{t('storedValue.recharge')}}</view>
  </sh-actionbar>
 </sh-scaffold>
</template>
