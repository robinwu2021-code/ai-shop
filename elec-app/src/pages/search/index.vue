<script setup lang="ts">
// 搜索结果（原型 e02 / e03）。
// 大小写、空格、横杠都不影响；输入里带厂牌自动过滤（可一键去掉）；只记得中间一截也能搜到。
// 原词一条都没命中时后端退到更短的前缀给「相近」的：顶上必须写出原词，
// 否则他会以为库里真有这个料号；最后留一条「按你输的询价」当出口。
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { api, errMsg } from "@/api";
import { ROUTES, go } from "@/shared/routes";
import { rememberSearch } from "@/shared/recent";
import type { ElecSearchResult } from "@shared/types";

const keyword = ref("");
const input = ref("");
const result = ref<ElecSearchResult | null>(null);
const failed = ref("");
const loading = ref(false);

onLoad((q) => {
  keyword.value = q?.keyword ? decodeURIComponent(String(q.keyword)) : "";
  input.value = keyword.value;
  void load();
});

async function load() {
  if (!keyword.value.trim()) return;
  loading.value = true;
  failed.value = "";
  try {
    result.value = await api.searchParts(keyword.value);
  } catch (e) {
    failed.value = errMsg(e);
  } finally {
    loading.value = false;
  }
}

function research() {
  const v = input.value.trim();
  if (!v || v === keyword.value) return;
  rememberSearch(v);
  keyword.value = v;
  void load();
}

/** 去掉厂牌过滤：他可能只是顺手把厂牌也粘进来了，而过滤掉的正是他要的那家 */
function dropMfr() {
  const r = result.value;
  if (!r) return;
  input.value = r.keyword;
  keyword.value = r.keyword;
  void load();
}

const hits = computed(() => result.value?.hits ?? []);
const empty = computed(() => !!result.value && hits.value.length === 0);
</script>

<template>
  <sh-scaffold title-key="title.search" :failed="!!failed" :failed-text="failed" @retry="load">
    <view class="sh-searchbox">
      <sh-icon name="search" :size="36" color="var(--sh-sub)"></sh-icon>
      <input v-model="input" class="grow txt-body" confirm-type="search" @confirm="research" />
    </view>

    <view v-if="result?.mfrFilter" class="sh-row sh-row--between filter">
      <text class="txt-sub">厂牌：{{ result.mfrFilter }}</text>
      <text class="sh-link" @tap="dropMfr">去掉 ✕</text>
    </view>
    <view v-if="result?.nearFrom" class="sh-notice sh-notice--warning filter">
      <text class="txt-sub">没有找到 <text class="txt-bold sh-num">{{ result.nearFrom }}</text>，下面是相近的料号</text>
    </view>

    <sh-empty v-if="empty" text="库里还没有这个料号" tip="照样可以询价，平台帮你找货"></sh-empty>

    <view class="sh-cells list">
      <view v-for="h in hits" :key="h.partNo" class="sh-cell" @tap="go(ROUTES.part, { partNo: h.partNo })">
        <el-part-card :hit="h"></el-part-card>
      </view>
    </view>

    <view v-if="result && (empty || result.nearFrom)" class="sh-card out" @tap="go(ROUTES.rfqCreate, { mpn: result.nearFrom || result.keyword })">
      <view class="sh-row sh-row--between">
        <text class="txt-sub">都不是？直接按你输的询价</text>
        <text class="sh-link">询价 ›</text>
      </view>
    </view>
    <text v-if="loading && !result" class="txt-caption sh-muted center">正在查…</text>
  </sh-scaffold>
</template>

<style scoped>
.grow {
  flex: 1;
}
.filter {
  margin-top: 20rpx;
}
.list {
  margin-top: 24rpx;
}
.out {
  margin-top: 24rpx;
}
.center {
  display: block;
  text-align: center;
  margin-top: 48rpx;
}
</style>
