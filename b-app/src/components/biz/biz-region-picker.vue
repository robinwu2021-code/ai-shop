<script setup lang="ts">
/**
 * 经营范围的**唯一**添加入口：一个分级列表走到底（市 › 区 › 街道 › 小区/村）。
 *
 * 方案 v3 定的形状：每一层的行长得一样 —— 有下级的带 › 可下钻、叶子直接勾、
 * 顶部那行是「整个本级」；任何一级都能搜；提报入口在叶子层末尾。
 * 此前「选小区」与「按区/街道」是两个入口、两套交互，而对店主它们是同一件事：「我做哪儿」。
 *
 * 不一次拉整棵树：全国到街道是 4.4 万行，店主真正会点开的只有其中一条路径。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { getLocation } from "@shared/ports/location";
import type { Community, CommunityApply, Region, ServiceArea } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  areas: ServiceArea[];
}>();
const emit = defineEmits<{
  (e: "update:visible", v: boolean): void;
  (e: "update:areas", v: ServiceArea[]): void;
  (e: "applied", a: CommunityApply): void;
}>();

const { t } = useI18n();

// ---------------------------------------------------------------- 导航
/** 面包屑。空 = 停在省级 */
const trail = ref<Region[]>([]);
const list = ref<Region[]>([]);
const loading = ref(false);
/** 当前停在的这一级（null = 省级列表） */
const current = computed(() => trail.value[trail.value.length - 1] ?? null);
/** 街道/镇是导航终点：这一层平铺聚落（小区/村），不再往下钻区划 */
const atLeaf = computed(() => current.value?.level === "STREET");

/** 全部已开通聚落。搜索与叶子层都用它；一次拉、按需过滤（当前量级是个位数到几百） */
const communities = ref<Community[]>([]);
const communitiesLoaded = ref(false);

async function ensureCommunities() {
  if (communitiesLoaded.value) return;
  try {
    communities.value = await api.mCommunities();
  } catch {
    communities.value = [];
  }
  communitiesLoaded.value = true;
}

async function loadLevel(parent?: string) {
  loading.value = true;
  try {
    list.value = await api.mRegions(parent);
  } catch {
    list.value = [];
    uni.showToast({ title: t("store.regionFailed"), icon: "none" });
  } finally {
    loading.value = false;
  }
}

async function open() {
  trail.value = [];
  keyword.value = "";
  applyOpen.value = false;
  await Promise.all([loadLevel(undefined), ensureCommunities()]);
}

watch(() => props.visible, (v) => { if (v) void open(); });

async function drill(r: Region) {
  if (!r.hasChild && r.level !== "STREET") return;
  if (kw.value) {
    // 从搜索结果下钻：面包屑要换成它的真实路径，否则「整个本级」与名字拼接都是错的
    const chain = await api.mRegionPath(r.regionCode).catch(() => [] as Region[]);
    trail.value = chain.length ? chain : [...trail.value, r];
  } else {
    trail.value = [...trail.value, r];
  }
  keyword.value = "";
  if (r.level !== "STREET") await loadLevel(r.regionCode);
}

async function backTo(i: number) {
  trail.value = trail.value.slice(0, i + 1);
  keyword.value = "";
  applyOpen.value = false;
  const cur = trail.value[i];
  if (!cur || cur.level !== "STREET") await loadLevel(cur?.regionCode);
}

// ---------------------------------------------------------------- 选中
function has(level: string, refCode: string) {
  return props.areas.some((a) => a.level === level && a.refCode === refCode);
}

/** 名字拼整条路径：光一个「西湖区」全国有好几个，两条同名的商家分不出删哪条 */
function pathName(leafName: string, extra?: Region | null) {
  return [...trail.value.map((x) => x.name), extra?.name, leafName].filter(Boolean).join(" / ");
}

function toggleRegion(r: Region & { path?: string }) {
  if (has(r.level, r.regionCode)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === r.level && a.refCode === r.regionCode)));
    return;
  }
  // 正在这一层里：路径不含自己；搜索命中的用服务端给的路径
  const inTrail = trail.value.some((x) => x.regionCode === r.regionCode);
  const name = r.path
    ? [r.path, r.name].filter(Boolean).join(" / ")
    : inTrail
      ? trail.value.slice(0, trail.value.findIndex((x) => x.regionCode === r.regionCode) + 1).map((x) => x.name).join(" / ")
      : pathName(r.name);
  emit("update:areas", [...props.areas, { level: r.level as ServiceArea["level"], refCode: r.regionCode, name }]);
}

function toggleCommunity(c: Community & { path?: string }) {
  if (has("COMMUNITY", c.communityNo)) {
    emit("update:areas", props.areas.filter((a) => !(a.level === "COMMUNITY" && a.refCode === c.communityNo)));
    return;
  }
  const name = c.path ? [c.path, c.name].join(" / ") : pathName(c.name);
  emit("update:areas", [...props.areas, { level: "COMMUNITY", refCode: c.communityNo, name }]);
}

// ---------------------------------------------------------------- 搜索（任何一级都能搜，P1 走服务端跨级搜索）
const keyword = ref("");
const kw = computed(() => keyword.value.trim());
/** 服务端命中：区划带从省到父级的路径，聚落带所在街道路径 */
const hitRegions = ref<Array<Region & { path: string }>>([]);
const hitCommunities = ref<Array<Community & { path: string }>>([]);
const searching = ref(false);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

watch(kw, (q) => {
  clearTimeout(searchTimer);
  if (q.length < 2) {
    hitRegions.value = [];
    hitCommunities.value = [];
    return;
  }
  searchTimer = setTimeout(async () => {
    searching.value = true;
    try {
      const r = await api.mRegionSearch(q);
      hitRegions.value = r.regions.map((x) => ({
        regionCode: x.regionCode, parentCode: "", level: x.level, name: x.name,
        enabled: true, hasChild: x.level !== "STREET", path: x.path,
      } as Region & { path: string }));
      hitCommunities.value = r.communities.map((x) => ({
        communityNo: x.communityNo, name: x.name, regionCode: x.regionCode ?? undefined, path: x.path,
      } as unknown as Community & { path: string }));
    } catch {
      // 搜索接口不在（老后端）：退回本地过滤，至少当前层还能搜
      hitRegions.value = list.value.filter((r) => r.name.includes(q)).map((r) => ({ ...r, path: "" }));
      hitCommunities.value = communities.value.filter((c) => c.name.includes(q)).slice(0, 30)
        .map((c) => ({ ...c, path: "" }));
    } finally {
      searching.value = false;
    }
  }, 250);
});

/** 区划行：搜索时是服务端命中，否则是本级 */
const levelRows = computed<Array<Region & { path?: string }>>(() =>
  kw.value ? hitRegions.value : list.value,
);
/** 聚落行：搜索时是服务端命中；叶子层是本街道下的 */
const settleRows = computed<Array<Community & { path?: string }>>(() => {
  if (kw.value) return hitCommunities.value;
  if (!atLeaf.value) return [];
  return communities.value.filter((c) => c.regionCode === current.value?.regionCode);
});
const nothing = computed(() =>
  !loading.value && !searching.value && kw.value.length !== 1 && !levelRows.value.length && !settleRows.value.length,
);

// ---------------------------------------------------------------- 叶子层提报（带官方村名词典）
const applyOpen = ref(false);
const applyName = ref("");
const pickedVillage = ref<Region | null>(null);
const dictSuggests = ref<Region[]>([]);
let dictTimer: ReturnType<typeof setTimeout> | undefined;

/** 「富城村村民委员会」→「富城村」：聚落叫的是地名，不是机构名 */
function cleanVillageName(official: string): string {
  return official.replace(/(村民委员会|居民委员会|村委会|居委会|委员会)$/, "") || official;
}

watch(applyName, (v: string) => {
  if (pickedVillage.value && v !== cleanVillageName(pickedVillage.value.name)) pickedVillage.value = null;
  clearTimeout(dictTimer);
  const q = v.trim();
  if (!q || pickedVillage.value || !atLeaf.value) {
    dictSuggests.value = [];
    return;
  }
  dictTimer = setTimeout(async () => {
    try {
      dictSuggests.value = (await api.mVillageDict(current.value!.regionCode, q)).slice(0, 5);
    } catch {
      dictSuggests.value = [];
    }
  }, 300);
});

function pickVillage(r: Region) {
  pickedVillage.value = r;
  applyName.value = cleanVillageName(r.name);
  dictSuggests.value = [];
}

async function submitApply() {
  const street = current.value;
  const name = applyName.value.trim();
  if (!street || !name) {
    uni.showToast({ title: t("store.applyNeedName"), icon: "none" });
    return;
  }
  try {
    // 定位尽力而为：裁决通过时聚落的坐标就来自这里（withinRadius 对空坐标恒 false）
    const loc = await getLocation();
    const a = await api.mApplyCommunity({
      name,
      regionCode: street.regionCode,
      kind: pickedVillage.value ? "VILLAGE" : "ESTATE",
      originCode: pickedVillage.value?.regionCode,
      latE6: loc ? Math.round(loc.lat * 1e6) : undefined,
      lngE6: loc ? Math.round(loc.lng * 1e6) : undefined,
    });
    emit("applied", a);
    applyOpen.value = false;
    applyName.value = "";
    pickedVillage.value = null;
    uni.showToast({ title: t("store.applySubmitted"), icon: "none" });
  } catch (e) {
    uni.showToast({ title: (e as Error)?.message || t("store.applyFailed"), icon: "none" });
  }
}

function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="mask" @tap="close">
    <view class="sheet" @tap.stop>
      <view class="sheet__head">
        <text class="sheet__title">{{ $t("store.picker.title") }}</text>
        <text class="sheet__count">{{ $t("store.picker.selected", { n: areas.length }) }}</text>
      </view>

      <view class="search">
        <sh-icon name="search" :size="18" color="var(--sh-sub)"></sh-icon>
        <input v-model="keyword" class="search__input" :maxlength="20" :placeholder="$t('store.picker.searchPh')" />
      </view>

      <!-- 面包屑：当前在哪一级。搜索时不显示 —— 结果是跨级的 -->
      <view v-if="!kw" class="crumb">
        <text class="crumb__i" @tap="backTo(-1)">{{ $t("store.regionRoot") }}</text>
        <text v-for="(x, i) in trail" :key="x.regionCode" class="crumb__i" :class="{ 'is-cur': i === trail.length - 1 }" @tap="backTo(i)">
          › {{ x.name }}
        </text>
      </view>

      <!-- 整个本级：中间层级也要能整个选，否则框一个区要点开十几个街道 -->
      <view v-if="!kw && current" class="whole" :class="{ 'is-on': has(current.level, current.regionCode) }" @tap="toggleRegion(current)">
        <text class="whole__t">{{ $t("store.picker.wholeLevel", { s: current.name }) }}</text>
        <text v-if="has(current.level, current.regionCode)" class="whole__on">{{ $t("store.picker.picked") }}</text>
        <text v-else-if="current.level === 'DISTRICT' || current.level === 'CITY'" class="whole__audit">{{ $t("store.picker.needAudit") }}</text>
      </view>

      <scroll-view scroll-y class="body">
        <text v-if="loading" class="hint">{{ $t("common.loading") }}</text>
        <template v-else>
          <!-- 区划行：同一种形状，有下级的带 › -->
          <view v-for="r in (atLeaf && !kw ? [] : levelRows)" :key="r.regionCode" class="row">
            <view class="row__main" @tap="drill(r)">
              <text class="row__name">{{ r.name }}</text>
              <text v-if="r.path" class="row__sub">{{ r.path }}</text>
            </view>
            <view class="row__check" :class="{ 'is-on': has(r.level, r.regionCode) }" @tap="toggleRegion(r)">
              <text v-if="has(r.level, r.regionCode)" class="row__tick">✓</text>
            </view>
            <sh-icon v-if="r.hasChild || r.level === 'STREET'" name="chevronRight" :size="18" color="var(--sh-sub)" @tap="drill(r)"></sh-icon>
          </view>

          <!-- 聚落行（小区/村同列）：叶子直接勾 -->
          <view v-for="c in settleRows" :key="c.communityNo" class="row" @tap="toggleCommunity(c)">
            <view class="row__main">
              <text class="row__name">{{ c.name }}</text>
              <text v-if="c.path || c.address" class="row__sub">{{ c.path || c.address }}</text>
            </view>
            <view class="row__check" :class="{ 'is-on': has('COMMUNITY', c.communityNo) }">
              <text v-if="has('COMMUNITY', c.communityNo)" class="row__tick">✓</text>
            </view>
          </view>

          <text v-if="nothing" class="hint">{{ $t("store.picker.searchEmpty") }}</text>

          <!-- 叶子层末尾：提报 -->
          <template v-if="atLeaf && !kw">
            <view v-if="!applyOpen" class="row row--apply" @tap="applyOpen = true">
              <text class="row__apply">{{ $t("store.picker.applyEntry") }}</text>
            </view>
            <view v-else class="apply">
              <text class="hint">{{ $t("store.applyToStreet", { s: current?.name }) }}</text>
              <input v-model="applyName" class="field__input" :maxlength="30" :placeholder="$t('store.dictHint')" />
              <view v-if="dictSuggests.length" class="apply__sug">
                <text v-for="d in dictSuggests" :key="d.regionCode" class="sh-chip" @tap="pickVillage(d)">{{ d.name }}</text>
              </view>
              <text v-if="pickedVillage" class="hint">{{ $t("store.dictPicked", { s: pickedVillage.name }) }}</text>
              <view class="apply__btns">
                <text class="sh-btn sh-btn--soft apply__go" @tap="submitApply">{{ $t("common.submit") }}</text>
                <text class="mini" @tap="applyOpen = false">{{ $t("common.cancel") }}</text>
              </view>
            </view>
          </template>
        </template>
      </scroll-view>

      <view class="foot">
        <view class="sh-btn" @tap="close">{{ $t("store.picker.done", { n: areas.length }) }}</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: var(--sh-scrim);
}
.sheet {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  height: 84vh;
  border-radius: 32rpx 32rpx 0 0;
  background: var(--sh-surface);
}
.sheet__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx 16rpx;
}
.sheet__title {
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.sheet__count {
  font-size: 26rpx;
  color: var(--sh-sub);
}
.search {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin: 0 24rpx;
  height: 80rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.search__input {
  flex: 1;
  font-size: 28rpx;
  color: var(--sh-ink);
}
.crumb {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin: 20rpx 32rpx 0;
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.crumb__i.is-cur {
  color: var(--sh-ink);
  font-weight: 600;
}
.whole {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16rpx 24rpx 0;
  padding: 20rpx 24rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
}
.whole.is-on {
  background: var(--sh-primary-tint);
}
.whole__t {
  font-size: 26rpx;
  color: var(--sh-ink);
}
.whole__on {
  padding: 4rpx 16rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
}
.whole__audit {
  font-size: 24rpx;
  color: var(--sh-warning);
}
.body {
  flex: 1;
  min-height: 0;
  margin-top: 12rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 32rpx;
  border-bottom: 2rpx solid var(--sh-line);
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__name {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__sub {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.row__check {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  border-radius: 9999px;
  border: 3rpx solid var(--sh-line);
  box-sizing: border-box;
}
.row__check.is-on {
  border-color: var(--sh-primary);
  background: var(--sh-primary);
}
.row__tick {
  font-size: 24rpx;
  color: var(--sh-on-primary);
}
.row--apply {
  border-bottom: none;
}
.row__apply {
  font-size: 26rpx;
  color: var(--sh-primary-text);
}
.apply {
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 12rpx 32rpx 24rpx;
}
.apply__sug {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.apply__btns {
  display: flex;
  gap: 16rpx;
}
.apply__go {
  flex: 1;
}
.hint {
  display: block;
  padding: 16rpx 32rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.mini {
  padding: 16rpx 28rpx;
  border-radius: 16rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 24rpx;
}
.foot {
  padding: 16rpx 24rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
  border-top: 2rpx solid var(--sh-line);
}
</style>
