<script setup lang="ts">
// 选社区自提点：定位推荐 → 选社区 → 选自提点 → 绑定归属（同时确定团长）。
// 拒绝定位时降级为列表手动选，不阻塞。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { fromE6, getLocation, openLocation } from "@shared/ports/location";
import { distance } from "@shared/utils/format";
import { ROUTES } from "@shared/utils/constants";
import type { Community, Pickup, RegionOption } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();

/**
 * 导航到这个取货点。**只有带坐标的点才显示这个入口** —— 存量点是手填地址建的，
 * 没有坐标；给它一个按钮，点了会打开一片空白，比没有更糟。
 */
function navTo(p: Pickup) {
  const c = fromE6(p.latE6, p.lngE6);
  if (c) openLocation({ ...c, name: p.name, address: p.address });
}
const community = useCommunityStore();
const cart = useCartStore();
const expanded = ref("");
const locating = ref(false);
const failed = ref(false);
/** 定位是否拿到。没拿到时后端不做距离过滤，返回的是全部 —— 这件事要对用户说明 */
const located = ref(true);
/** 用户主动切到了「全部已开通社区」 */
const showingAll = ref(false);
/** 正在选区域（附近没有时的第一步） */
const pickingRegion = ref(false);
/** 已选中的区域，用于列表标题与「换个区」 */
const region = ref<RegionOption | null>(null);

/** 选定一个区 → 只列这个区的社区 */
async function chooseRegion(r: RegionOption) {
  locating.value = true;
  failed.value = false;
  try {
    await community.loadAll(r.regionCode);
    region.value = r;
    pickingRegion.value = false;
    showingAll.value = true;
    expanded.value = community.list[0]?.communityNo ?? "";
  } catch (e) {
    failed.value = true;
    console.error("[community] 加载区域内社区失败", e);
  } finally {
    locating.value = false;
  }
}

/** 退回区域列表重选 */
function backToRegions() {
  region.value = null;
  keyword.value = "";
  community.list = [];
  pickingRegion.value = true;
}

/**
 * 附近没有 → 看全部。**空不能是死路**：这一页是新用户的第一屏，
 * 停在「暂未开通」而没有下一步，等于在第一屏劝退。
 */
async function browseAll() {
  locating.value = true;
  failed.value = false;
  try {
    await community.loadAll(region.value?.regionCode);
    showingAll.value = true;
    expanded.value = community.list[0]?.communityNo ?? "";
  } catch (e) {
    failed.value = true;
    console.error("[community] 加载全部社区失败", e);
  } finally {
    locating.value = false;
  }
}

/*
 * **这一页是新用户的第一屏**，所以它失败时的样子比成功时的样子更要紧。
 *
 * 原先只有一个 `v-for`：请求一挂，列表就是空数组，页面上除了标题栏什么都没有 ——
 * 用户看到的是一片白，没有任何提示、也没有可点的东西，连「是不是我网不好」
 * 都无从判断。而错误本身变成一个未捕获的 Promise rejection，控制台之外无人知晓。
 *
 * 三种状态要分开说，因为用户的下一步动作不同：
 *   定位中   → 等一下
 *   加载失败 → 重试（多半是网络/域名白名单）
 *   真的没有 → 别等了，这一带还没开通，先去逛商品
 */
async function load() {
  locating.value = true;
  failed.value = false;
  showingAll.value = false;
  try {
    /*
     * **定位结果要传下去。**
     *
     * 早先这里是 `await getLocation()` 然后把返回值丢掉、`loadNearby()` 不带参数 ——
     * 后端于是永远走「无坐标」分支：距离恒 0、排序退化成库序，
     * 「附近社区」四个字名不副实，而页面看起来完全正常。
     */
    const at = await getLocation();
    located.value = !!at;
    await community.loadNearby(at?.lat, at?.lng);
    /*
     * **附近没有就直接把全部列出来**，而不是先给一个空页面再让他点一次「查看全部」。
     *
     * 能走到这一页有两种人：被系统推来的（附近有，正常选），
     * 和自己点进来的（多半正是因为附近没有、他要手动挑一个）。
     * 对后者，那一次点击纯属多余 —— 他要的东西就在按钮后面。
     */
    if (!community.list.length) {
      /*
       * **附近没有 → 先给区域，而不是把全国的小区一股脑铺开。**
       *
       * 手动找一个自提点，用户脑子里的第一层是「哪个区」，不是「哪个小区」。
       * 直接给社区平铺，在只有两个演示社区时看着还行，
       * 到几百个小区就是一屏无从下手的名字。
       */
      await community.loadRegions();
      pickingRegion.value = community.regions.length > 0;
      if (!pickingRegion.value) {
        // 一个挂了区划的社区都没有（早期数据），那就退回平铺，总比空页面强
        await community.loadAll();
        showingAll.value = true;
      }
    }
    expanded.value = community.list[0]?.communityNo ?? "";
  } catch (e) {
    // 吞掉错误但**在界面上说出来** —— 静默失败是这一页原来的病
    failed.value = true;
    console.error("[community] 加载附近自提点失败", e);
  } finally {
    locating.value = false;
  }
}

/**
 * 手动找社区/自提点。**同时按名字、地址与自提点名匹配** ——
 * 用户嘴里的「区域」多半是「西湖区」「文一西路」，那些字在地址里，不在社区名里；
 * 而 `cityCode` / `regionCode` 是运营后补的字段，现在大多为空，
 * 拿它做筛选器只会得到一堆「未分区」。
 */
const keyword = ref("");
const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return community.list;
  return community.list.filter((c) =>
    [c.name, c.address, ...(c.pickups ?? []).map((p) => p.name)]
      .filter(Boolean)
      .some((v) => String(v).toLowerCase().includes(k)),
  );
});

/** 与后端 shop.community.nearby-radius-m 同一口径。端上只用来决定「要不要提醒」 */
const NEARBY_RADIUS_M = 5000;

async function choose(c: Community, p: Pickup) {
  /*
   * **超出服务半径要把真实距离说出来，但不拦。**
   *
   * 异地下单是真实场景（给父母下单、出差前囤货），拦掉是错的；
   * 而不声不响地让人绑一个 1000 公里外的自提点，他会在取货那天才发现。
   * 中间那条路是：让他自己看见那个数字，然后自己决定。
   */
  const far = (p.distance ?? c.distance ?? 0) > NEARBY_RADIUS_M;
  if (far) {
    const ok = await confirm({ title: String(t("common.farTitle")), hint: String(t("common.farTip", { d: distance(p.distance ?? c.distance ?? 0) })) });
    if (!ok) return;
  }

  const switching = community.bound && community.pickup?.pickupNo !== p.pickupNo;
  if (switching) {
    const ok = await confirm({ title: String(t("community.switchTitle")), hint: String(t("community.switchTip")) });
    if (!ok) return;
  }
  await community.bind(c, p);
  if (switching) await cart.refreshOnCommunityChange();
  uni.showToast({ title: String(t("community.bound")), icon: "none" });
  /*
   * 绑定完回哪儿：**这一页有两种到达方式**。
   * 从首页点「切社区」进来 —— 有返回栈，navigateBack 正确；
   * 而没归属的新用户是被**直接送到这里当首屏**的 —— 此时返回栈是空的，
   * navigateBack 无声失败，人就卡在选社区页，选中了也走不掉。
   * 后者恰恰是每个新用户的第一屏，所以兜底不是可选项。
   */
  setTimeout(() => {
    const stack = getCurrentPages();
    if (stack.length > 1) {
      uni.navigateBack();
    } else {
      uni.switchTab({ url: ROUTES.home });
    }
  }, 400);
}

onLoad(load);
</script>

<template>
  <sh-scaffold title-key="community.title">
    <text v-if="locating" class="txt-sub hint">{{ $t("community.locating") }}</text>

    <view v-else-if="failed" class="state">
      <text class="txt-body state__title">{{ $t("community.failed") }}</text>
      <text class="txt-sub state__tip">{{ $t("community.failedTip") }}</text>
      <view class="txt-body state__btn" @tap="load">
        <text>{{ $t("community.retry") }}</text>
      </view>
    </view>

    <!-- 第一步：选区域。只列有已开通社区的区，并把社区数摆在旁边 -->
    <view v-else-if="pickingRegion" class="rg">
      <text class="txt-sub rg__tip">{{ $t("common.pickRegion") }}</text>
      <view
        v-for="r in community.regions"
        :key="r.regionCode"
        class="rg__item sh-row sh-row--between"
        @tap="chooseRegion(r)"
      >
        <view class="rg__main">
          <text class="txt-body">{{ r.name }}</text>
          <text class="txt-caption rg__city">{{ r.cityName }}</text>
        </view>
        <text class="sh-chip">{{ $t("common.nCommunities", { n: r.communityCount }) }}</text>
      </view>
    </view>

    <view v-else-if="!community.list.length" class="state">
      <!--
        走到这里 = 附近没有、**区域清单也是空的**（区域块在上面先命中）。
        那才是真的什么都没有：一个挂了区划的已开通社区都不存在。
      -->
      <text class="txt-body state__title">{{ $t("community.empty") }}</text>
      <text class="txt-sub state__tip">{{ $t("community.emptyTip") }}</text>
      <view class="txt-body state__btn" @tap="browseAll">
        <text>{{ $t("common.browseAll") }}</text>
      </view>
    </view>

    <text v-else-if="showingAll" class="txt-sub hint">{{ $t("common.allTip") }}</text>
    <text v-else-if="!located" class="txt-sub hint">{{ $t("common.noLocation") }}</text>

    <view v-if="region" class="txt-sub rg__back" @tap="backToRegions">
      <text>{{ region.cityName }} · {{ region.name }} · {{ $t("common.changeRegion") }}</text>
    </view>

    <view v-if="community.list.length" class="search">
      <input
        maxlength="32"
        v-model="keyword"
        class="txt-sub search__input"
        :placeholder="String($t('common.searchHint'))"
        confirm-type="search"
      />
    </view>

    <text v-if="community.list.length && !shown.length" class="txt-sub hint">
      {{ $t("common.searchEmpty") }}
    </text>

    <view v-for="c in shown" :key="c.communityNo" class="sh-card cm">
      <view
        class="cm__head sh-row"
        @tap="expanded = expanded === c.communityNo ? '' : c.communityNo"
      >
        <view class="sh-fill">
          <text class="txt-strong cm__name">{{ c.name }}</text>
          <text class="txt-caption cm__addr">{{ c.address }}</text>
        </view>
        <text class="sh-chip">{{ distance(c.distance) }}</text>
      </view>

      <view v-if="expanded === c.communityNo" class="pk-list sh-row">
        <view
          v-for="p in c.pickups"
          :key="p.pickupNo"
          class="pk sh-row"
          :class="{ 'is-on': community.pickup?.pickupNo === p.pickupNo }"
          @tap="choose(c, p)"
        >
          <text class="pk__avatar">{{ p.hostAvatar }}</text>
          <view class="sh-fill">
            <text class="txt-strong pk__name">{{ p.name }}</text>
            <text class="txt-caption pk__sub">
              {{ p.hostName }} · {{ p.openHours }} · {{ p.arrivalDesc }}
            </text>
            <text v-if="p.address" class="txt-caption">{{ p.address }}</text>
          </view>
          <view class="pk__right">
            <text class="txt-caption pk__dist sh-num">{{ distance(p.distance) }}</text>
            <text v-if="p.latE6 != null" class="txt-caption pk__nav" @tap.stop="navTo(p)">{{ $t("community.navigate") }}</text>
          </view>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.rg__tip {
  display: block;
  padding: 8rpx 0 24rpx;
}
.rg__item {
  padding: 28rpx 24rpx;
  margin-bottom: 16rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
}
.rg__main {
  display: flex;
  flex-direction: column;
}

.rg__city {
  margin-top: 8rpx;
}
.rg__back {
  padding: 16rpx 0 24rpx;
  color: var(--sh-primary-text);
}
.search {
  padding: 0 0 24rpx;
}
.search__input {
  height: 76rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  color: var(--sh-ink);
}
.state {
  padding: 96rpx 48rpx;
  text-align: center;
}
.state__title {
  display: block;
}
.state__tip {
  display: block;
  margin-top: 16rpx;
}
.state__btn {
  display: inline-block;
  margin-top: 40rpx;
  padding: 20rpx 56rpx;
  border-radius: 24rpx;
  background: var(--sh-primary);
  /* 白字在 fresh 这类亮主色上只有 2.27 —— 前景必须按对比度算，走 token */
  color: var(--sh-on-primary);
}
.hint {
  display: block;
  text-align: center;
  padding: 28rpx 0;
}
/* 面色 / 圆角 / 内边距交给 `.sh-card` —— 此前这三行是把它照抄了一遍。
   内边距因此从 28rpx 变成 C 端的密度档 32rpx（`--sh-pad-card` 没被 C 端覆盖），
   差 2px：**那正是密度变量存在的意义** —— 各页各写一个数，调密度时就得逐页找。 */
.cm {
  margin-bottom: 20rpx;
}
.cm__head {
  gap: 20rpx;
}

.cm__name {
  display: block;
}
.cm__addr {
  display: block;
  margin-top: 8rpx;
}
/* 自提点：每个是独立浅色块，靠间距分隔，无分隔线 */
.pk-list {
  margin-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.pk {
  gap: 20rpx;
  background: var(--sh-faint);
  border-radius: 32rpx;
  padding: 24rpx;
}
.pk.is-on {
  background: var(--sh-primary-tint);
}
.pk__avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
  text-align: center;
  line-height: 72rpx;
  font-size: 36rpx;
  flex-shrink: 0;
}

.pk__name {
  display: block;
}
.pk__sub {
  display: block;
  margin-top: 8rpx;
}

.pk__right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8rpx;
}
.pk__nav {
  padding: 6rpx 16rpx;
  border-radius: 999px;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
}
.pk__dist {
  flex-shrink: 0;
}
</style>
