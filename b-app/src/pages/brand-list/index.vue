<script setup lang="ts">
import {ref} from 'vue';import {onLoad,onShow} from '@dcloudio/uni-app';import {useI18n} from 'vue-i18n';import {api} from '@/api';import {managementGo} from '@/shared/management';import type {Brand} from '@/api/management-types';
const loaded=ref(false);
const {t}=useI18n();const m=ref('');const keyword=ref('');const rows=ref<Brand[]>([]);const next=ref<string|null>(null);const busy=ref(false);const failed=ref(false);const canCreate=ref(false);let generation=0;
async function load(more=false){if(busy.value&&more)return;const run=++generation;busy.value=true;failed.value=false;try{const page=await api.mBrands(m.value,{keyword:keyword.value||undefined,cursor:more?next.value||undefined:undefined});if(run===generation){rows.value=more?[...rows.value,...page.items]:page.items;next.value=page.nextCursor}}catch{if(run===generation)failed.value=true}finally{if(run===generation){busy.value=false;loaded.value=true}}}
onLoad(q=>{m.value=q?.m||''});onShow(async()=>{await load();try{canCreate.value=(await api.mManagementContext()).spaces.find(s=>s.managementNo===m.value)?.actions.includes('brand.manage')||false}catch{canCreate.value=false}});
</script>
<template><sh-scaffold title-key="management.brands"><view class="sh-card sh-row"><input maxlength="32" v-model="keyword" class="field__input sh-fill" :placeholder="t('management.searchBrand')" @confirm="load()"/><view class="sh-btn sh-btn--soft sh-btn--sm" @tap="load()">{{t('management.search')}}</view></view>
 <view v-if="failed" class="sh-card" @tap="load()">{{t('management.retry')}}</view>
 <view v-for="brand in rows" :key="brand.brandNo" class="sh-card sh-row sh-row--between" @tap="managementGo('brand_detail',{b:brand.brandNo,m})"><text class="txt-title">{{brand.name}}</text><text class="sh-chip">{{t('management.state.'+brand.status)}}</text><sh-go/></view>
 <sh-empty :pending="!loaded" :failed="failed" @retry="load()" v-if="!busy&&!failed&&!rows.length" :text="t('management.empty')"/><view v-if="next" class="sh-btn sh-btn--soft" @tap="load(true)">{{t('management.more')}}</view>
 <sh-actionbar v-if="canCreate"><view class="sh-btn" @tap="managementGo('brand_edit',{m})">{{t('management.createBrand')}}</view></sh-actionbar></sh-scaffold></template>
