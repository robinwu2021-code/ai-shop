<script setup lang="ts">
import { computed } from "vue";
import type { OrderPointsEarning } from "@shared/types";
const props = defineProps<{ rows: OrderPointsEarning[]; failed?: boolean }>();
const namedRows = computed(() => props.rows.filter(row => row.name?.trim()));
const nameMissing = computed(() => namedRows.value.length !== props.rows.length);
defineEmits<{retry: []}>();
</script>
<template>
  <view v-if="rows.length || failed" class="sh-card sh-mt-sm">
    <text class="txt-title">{{ $t('points.orderEarning') }}</text>
    <view v-for="row in namedRows" :key="`${row.orderNo}:${row.typeCode}`" class="sh-mt-sm">
      <view class="sh-row sh-row--between"><text>{{ row.name }}</text><text v-if="row.state !== 'NONE'">{{ $t(`points.earning${row.state}`) }}</text></view>
      <view class="sh-row sh-row--between txt-caption sh-muted"><text>{{ row.typeCode }}</text><text>{{ row.currency }}</text></view>
      <template v-if="row.state !== 'NONE'">
      <view class="sh-row sh-row--between"><text>{{ $t('points.earningExpected') }}</text><text class="sh-num">{{ row.expectedPoints }}</text></view>
      <view class="sh-row sh-row--between"><text>{{ $t('points.bucketPENDING') }}</text><text class="sh-num">{{ row.pendingPoints }}</text></view>
      <view class="sh-row sh-row--between"><text>{{ $t('points.earningCredited') }}</text><text class="sh-num">{{ row.availablePoints }}</text></view>
      <view v-if="row.reclaimedPoints" class="sh-row sh-row--between"><text>{{ $t('points.earningReclaimed') }}</text><text class="sh-num">{{ row.reclaimedPoints }}</text></view>
      <text v-if="row.reason" class="txt-caption sh-muted">{{ $t(row.reason === 'POINTS_ORDER_CANCELLED' ? 'points.earningCancelled' : row.reason === 'POINTS_POOL_INSUFFICIENT' ? 'points.earningBudgetPending' : row.state === 'SKIPPED' ? 'points.earningNotGranted' : 'points.earningDelayed') }}</text>
      </template>
      <view v-if="row.redemptionState && row.redemptionState !== 'NONE'" class="sh-row sh-row--between"><text>{{ $t(`points.redemption${row.redemptionState}`) }}</text><text class="sh-num">{{ row.usedPoints }}</text></view>
      <template v-if="row.refundState && row.refundState !== 'NONE'">
        <view class="sh-row sh-row--between"><text>{{ $t('points.refundPoints') }}</text><text>{{ $t(`points.refund${row.refundState}`) }}</text></view>
        <view class="sh-row sh-row--between"><text>{{ $t('points.returnedPoints') }}</text><text class="sh-num">{{ row.returnedPoints }}</text></view>
      </template>
    </view>
    <view v-if="failed || nameMissing" class="sh-btn sh-btn--muted sh-mt-sm" @tap="$emit('retry')">{{ $t('points.earningRetry') }}</view>
  </view>
</template>
