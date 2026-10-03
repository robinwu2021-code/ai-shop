<script setup lang="ts">
/**
 * 选城市 —— **让「在别处填地址」这件事成立**。
 *
 * <p>此前选择地点页的搜索只围着**当前定位**搜（`searchPlacesNearNative`）：
 * 人在深圳给北京的家填地址，搜「望京」搜不到 —— 而这不是一条报错，
 * 是一个空列表，他会以为那个地方不存在。
 *
 * <p>四段，按「他最可能选哪个」排：定位到的那个 → 最近用过的 → 热门 → 全部。
 * 全部那一段带搜索与拼音索引 —— 370 个市，不给索引就只能滑。
 */
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { RegionNode } from "@shared/types";
import { useLocationStore } from "@/stores/location";
import { pickedCity } from "@/shared/address-pick";
import { RECENT_CITY_KEY, HOT_CITY_CODES } from "@shared/utils/constants";

const { t } = useI18n();
const location = useLocationStore();

const all = ref<RegionNode[]>([]);
const keyword = ref("");
/** 这一次没取到。**与「一个城市都没有」是两件事** —— 后者不可能发生 */
const failed = ref(false);
const loaded = ref(false);

/**
 * 定位到的那个市。
 *
 * <p>由 `regionCode`（区县 6 位）截前 4 位得到 —— **不另发一次请求**：
 * 城市名就在下面那张全量表里，再问一次后端等于把同一个事实存两处。
 */
const locatedCity = computed(() => {
  const region = location.coarseRegion?.code;
  if (!region) return null;
  return all.value.find((c) => c.regionCode === region.slice(0, 4)) ?? null;
});

/** 最近用过的（本机记，最多三个）。换设备就没了 —— 它是顺手不是资料 */
const recent = ref<RegionNode[]>([]);

const hot = computed(() => HOT_CITY_CODES
  .map((code) => all.value.find((c) => c.regionCode === code))
  .filter((c): c is RegionNode => !!c));

/** 搜索命中的。空关键词时给全部 */
const shown = computed(() => {
  const kw = keyword.value.trim();
  if (!kw) return all.value;
  return all.value.filter((c) => c.name.includes(kw));
});

async function load() {
  failed.value = false;
  try {
    all.value = await api.regions(undefined, "CITY");
  } catch {
    failed.value = true;
  }
  loaded.value = true;
  try {
    const raw = uni.getStorageSync(RECENT_CITY_KEY);
    const codes: string[] = raw ? JSON.parse(String(raw)) : [];
    recent.value = codes
      .map((code) => all.value.find((c) => c.regionCode === code))
      .filter((c): c is RegionNode => !!c);
  } catch {
    // 本机存的东西坏了不是错误：当作没有
    recent.value = [];
  }
}

function choose(c: RegionNode) {
  try {
    const raw = uni.getStorageSync(RECENT_CITY_KEY);
    const codes: string[] = raw ? JSON.parse(String(raw)) : [];
    const next = [c.regionCode, ...codes.filter((x) => x !== c.regionCode)].slice(0, 3);
    uni.setStorageSync(RECENT_CITY_KEY, JSON.stringify(next));
  } catch {
    // 存不下就算了 —— 少一个「最近用过」不影响他选城市
  }
  pickedCity.offer({ code: c.regionCode, name: c.name });
  uni.navigateBack();
}

onMounted(load);
</script>

<template>
  <sh-scaffold title-key="cityPick.title">
    <view class="sh-card searchbox">
      <input
        v-model="keyword"
        class="field__input"
        maxlength="16"
        :placeholder="$t('cityPick.searchPh')"
      />
    </view>

    <sh-empty v-if="loaded && failed" line failed @retry="load"></sh-empty>

    <!-- 有关键词时结果顶掉上面那三段：别让他在四份列表里找自己刚搜的那个 -->
    <template v-if="!keyword.trim()">
      <view v-if="locatedCity" class="sh-card block">
        <text class="txt-strong block__title">{{ $t("cityPick.located") }}</text>
        <view class="sh-row--divided" @tap="choose(locatedCity)">
          <text class="txt-body">{{ locatedCity.name }}</text>
        </view>
      </view>

      <view v-if="recent.length" class="sh-card block">
        <text class="txt-strong block__title">{{ $t("cityPick.recent") }}</text>
        <view class="chips sh-row sh-wrap">
          <text v-for="c in recent" :key="c.regionCode" class="sh-chip" @tap="choose(c)">
            {{ c.name }}
          </text>
        </view>
      </view>

      <view v-if="hot.length" class="sh-card block">
        <text class="txt-strong block__title">{{ $t("cityPick.hot") }}</text>
        <view class="chips sh-row sh-wrap">
          <text v-for="c in hot" :key="c.regionCode" class="sh-chip" @tap="choose(c)">
            {{ c.name }}
          </text>
        </view>
      </view>
    </template>

    <view v-if="loaded && !failed" class="sh-card block">
      <text class="txt-strong block__title">{{ $t("cityPick.all") }}</text>
      <view v-for="c in shown" :key="c.regionCode" class="sh-row--divided" @tap="choose(c)">
        <text class="txt-body">{{ c.name }}</text>
      </view>
      <!-- 搜不到要说出来：一个空列表会被读成「这个城市不存在」 -->
      <text v-if="!shown.length" class="sh-hint">{{ $t("cityPick.noResults") }}</text>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.searchbox {
  margin-bottom: 20rpx;
}
.block {
  margin-bottom: 20rpx;
}
.block__title {
  display: block;
  margin-bottom: 8rpx;
}
.chips {
  gap: 16rpx;
}
</style>
