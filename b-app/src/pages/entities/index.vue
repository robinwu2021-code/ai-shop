<script setup lang="ts">
/**
 * 证照与账户（03 屏）。**从「我的」进的独立入口。**
 *
 * 与门店选择（`store-pick`）是两条互不打架的路：
 *   选门店 = 我现在要在哪家店干活（日常，每天都可能点）
 *   这一页 = 我名下有哪几张营业执照、它们的资料对不对（低频，一年动不了几次）
 *
 * **打开这一页不会切换当前在管的门店** —— 这是产品方案 §2.5 定下的：
 * 两条「进店」的路互相打架的话，商家迟早分不清自己现在在管哪家店。
 *
 * 对外不叫「主体」「实体」（老板不认识那两个词），一律叫「证照」。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import type { Entity } from "@shared/types";

const { t } = useI18n();
const rows = ref<Entity[]>([]);
const loading = ref(false);
const failed = ref(false);

/** 一个账号最多几张证照。与后端 `MAX_ENTITIES_PER_ACCOUNT` 同一个数 */
const MAX = 5;
/**
 * 到上限就把入口**变灰并说明原因**，而不是让他填完一整张表再被拒。
 * 「点了才报错」在这里尤其糟：新增证照要走入驻申请，那是一张长表。
 */
const atLimit = computed(() => rows.value.length >= MAX);

async function load() {
  loading.value = true;
  failed.value = false;
  try {
    rows.value = await api.mEntities();
  } catch (e) {
    failed.value = true;
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

onShow(load);

/** 状态 → 一句人话 + 一个色调。**不直接把状态码显给商家** */
function statusText(s: string): string {
  if (s === "ACTIVE") return t("entities.stActive");
  if (s === "PENDING_LICENSE") return t("entities.stPending");
  if (s === "REVIEWING" || s === "PENDING") return t("entities.stReviewing");
  if (s === "REJECTED") return t("entities.stRejected");
  return t("entities.stClosed");
}
function statusTone(s: string): string {
  if (s === "ACTIVE") return "ok";
  if (s === "PENDING_LICENSE" || s === "REVIEWING" || s === "PENDING") return "warn";
  return "off";
}

function open(no: string) {
  uni.navigateTo({ url: `${ROUTES.entityDetail}?entityNo=${no}` });
}

/**
 * 新增一张证照 = 走入驻申请。**不是另建一条捷径**：
 * 新证照要平台看过执照才算数，而那条路已经存在且是唯一那条。
 */
function addOne() {
  if (atLimit.value) return;
  uni.navigateTo({ url: ROUTES.apply });
}
</script>

<template>
  <sh-scaffold title-key="entities.title">
    <text class="sh-h1">{{ $t("entities.heading") }}</text>
    <text class="hint">{{ $t("entities.hint") }}</text>

    <view v-if="loading && !rows.length" class="hint">{{ $t("common.loading") }}</view>
    <view v-else-if="failed && !rows.length" class="hint">{{ $t("common.loadFailed") }}</view>

    <view class="list">
      <view v-for="e in rows" :key="e.entityNo" class="sh-card item" @tap="open(e.entityNo)">
        <view class="item__main">
          <text class="item__name">
            {{ e.name }}
            <text v-if="e.isPrimary" class="sh-chip item__chip">{{ $t("entities.primary") }}</text>
          </text>
          <text class="item__sub">
            <text class="dot" :class="'is-' + statusTone(e.status)"></text>
            {{ statusText(e.status) }} · {{ $t("entities.storeCount", { n: e.storeCount }) }}
          </text>
        </view>
        <sh-icon name="chevronRight" :size="22" color="var(--sh-sub)"></sh-icon>
      </view>
    </view>

    <view class="sh-btn add" :class="{ 'is-disabled': atLimit }" @tap="addOne">
      {{ $t("entities.add") }}
    </view>
    <text v-if="atLimit" class="hint center">{{ $t("entities.atLimit", { n: MAX }) }}</text>
    <text class="hint center">{{ $t("entities.footHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.hint.center {
  margin-top: 20rpx;
  text-align: center;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 24rpx;
}
.item {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.item__main {
  flex: 1;
  min-width: 0;
}
.item__name {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.item__chip {
  margin-inline-start: 12rpx;
  font-weight: 400;
}
.item__sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.dot {
  display: inline-block;
  width: 14rpx;
  height: 14rpx;
  border-radius: 9999px;
  margin-inline-end: 8rpx;
  background: var(--sh-sub);
}
.dot.is-ok {
  /* 此前写的是 var(--sh-ok, #22a06b) —— 而皮肤里**没有 --sh-ok 这个变量**
     （语义色叫 --sh-success），于是一直落在兜底的硬编码色上：换肤与深色模式都跟不上 */
  background: var(--sh-success);
}
.dot.is-warn {
  /* 同上：--sh-warn 不存在，语义色叫 --sh-warning */
  background: var(--sh-warning);
}
.dot.is-off {
  background: var(--sh-line);
}
.add {
  margin-top: 32rpx;
}
.add.is-disabled {
  opacity: 0.5;
}
</style>
