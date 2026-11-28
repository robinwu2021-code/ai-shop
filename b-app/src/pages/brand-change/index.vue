<script setup lang="ts">
import {ref} from 'vue';import {onLoad,onShow} from '@dcloudio/uni-app';import {useI18n} from 'vue-i18n';import {api} from '@/api';import type {SvFinancialBrandChange} from '@/api/stored-value-finance';
// 入退连锁已校验通过当场生效、不走审批（TDD-平台基础库换表「连锁按权益准入」）；这一页只剩储值期间入退的两阶段进度与恢复
const {t}=useI18n();const c=ref(''),m=ref('');const row=ref<SvFinancialBrandChange|null>(null);const busy=ref(false),failed=ref(false);
async function load(){try{row.value=await api.mSvFinancialBrandChange(c.value,m.value);failed.value=false}catch{failed.value=true}}
async function resume(){if(busy.value||!row.value)return;busy.value=true;try{row.value=await api.mSvFinancialBrandResume(c.value,{managementNo:m.value});failed.value=false}catch{failed.value=true}finally{busy.value=false}}
onLoad(q=>{c.value=q?.c||'';m.value=q?.m||''});onShow(load);
</script>
<template><sh-scaffold title-key="management.brandChange"><view v-if="failed" class="sh-card" @tap="load">{{t('management.retry')}}</view><template v-if="row"><view class="sh-card"><text class="sh-chip">{{t('management.state.'+row.state)}}</text><view v-if="row.state!=='COMPLETED'" class="txt-sub sh-muted">{{t('management.blocker.FINANCIAL_COORDINATION_PENDING')}}</view></view><sh-actionbar v-if="row.state!=='COMPLETED'"><view class="sh-btn" @tap="resume">{{t(busy?'management.loading':'svGov.retry')}}</view></sh-actionbar></template></sh-scaffold></template>
