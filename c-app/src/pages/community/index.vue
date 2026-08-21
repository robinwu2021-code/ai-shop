<script setup lang="ts">
// 选社区自提点：定位推荐 → 选社区 → 选自提点 → 绑定归属（同时确定团长）。
// 拒绝定位时降级为列表手动选，不阻塞。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { useCommunityStore } from "@/stores/community";
import { useCartStore } from "@/stores/cart";
import { getLocation } from "@shared/ports/location";
import { distance } from "@shared/utils/format";
import { ROUTES } from "@shared/utils/constants";
import type { Community, Pickup } from "@shared/types";

const { t } = useI18n();
const community = useCommunityStore();
const cart = useCartStore();
const expanded = ref("");
const locating = ref(false);
const failed = ref(false);
/** 定位是否拿到。没拿到时后端不做距离过滤，返回的是全部 —— 这件事要对用户说明 */
const located = ref(true);
/** 用户主动切到了「全部已开通社区」 */
const showingAll = ref(false);

/**
 * 附近没有 → 看全部。**空不能是死路**：这一页是新用户的第一屏，
 * 停在「暂未开通」而没有下一步，等于在第一屏劝退。
 */
async function browseAll() {
  locating.value = true;
  failed.value = false;
  try {
    await community.loadAll();
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
      await community.loadAll();
      showingAll.value = true;
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
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: String(t("community.farTitle")),
        content: String(t("community.farTip", { d: distance(p.distance ?? c.distance ?? 0) })),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }

  const switching = community.bound && community.pickup?.pickupNo !== p.pickupNo;
  if (switching) {
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: String(t("community.switchTitle")),
        content: String(t("community.switchTip")),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
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
    <text v-if="locating" class="hint">{{ $t("community.locating") }}</text>

    <view v-else-if="failed" class="state">
      <text class="state__title">{{ $t("community.failed") }}</text>
      <text class="state__tip">{{ $t("community.failedTip") }}</text>
      <view class="state__btn" @tap="load">
        <text>{{ $t("community.retry") }}</text>
      </view>
    </view>

    <view v-else-if="!community.list.length" class="state">
      <text class="state__title">{{ $t("community.empty") }}</text>
      <text class="state__tip">{{ $t("community.emptyTip") }}</text>
      <view class="state__btn" @tap="browseAll">
        <text>{{ $t("community.browseAll") }}</text>
      </view>
    </view>

    <text v-else-if="showingAll" class="hint">{{ $t("community.allTip") }}</text>
    <text v-else-if="!located" class="hint">{{ $t("community.noLocation") }}</text>

    <view v-if="community.list.length" class="search">
      <input
        v-model="keyword"
        class="search__input"
        :placeholder="String($t('community.searchHint'))"
        confirm-type="search"
      />
    </view>

    <text v-if="community.list.length && !shown.length" class="hint">
      {{ $t("community.searchEmpty") }}
    </text>

    <view v-for="c in shown" :key="c.communityNo" class="cm">
      <view
        class="cm__head"
        @tap="expanded = expanded === c.communityNo ? '' : c.communityNo"
      >
        <view class="cm__main">
          <text class="cm__name">{{ c.name }}</text>
          <text class="cm__addr">{{ c.address }}</text>
        </view>
        <text class="sh-chip">{{ distance(c.distance) }}</text>
      </view>

      <view v-if="expanded === c.communityNo" class="pk-list">
        <view
          v-for="p in c.pickups"
          :key="p.pickupNo"
          class="pk"
          :class="{ 'is-on': community.pickup?.pickupNo === p.pickupNo }"
          @tap="choose(c, p)"
        >
          <text class="pk__avatar">{{ p.hostAvatar }}</text>
          <view class="pk__main">
            <text class="pk__name">{{ p.name }}</text>
            <text class="pk__sub">
              {{ p.hostName }} · {{ p.openHours }} · {{ p.arrivalDesc }}
            </text>
          </view>
          <text class="pk__dist sh-num">{{ distance(p.distance) }}</text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.search {
  padding: 0 0 24rpx;
}
.search__input {
  height: 76rpx;
  padding: 0 24rpx;
  border-radius: 24rpx;
  background: var(--sh-surface);
  font-size: 26rpx;
  color: var(--sh-ink);
}
.state {
  padding: 96rpx 48rpx;
  text-align: center;
}
.state__title {
  display: block;
  font-size: 30rpx;
  color: var(--sh-ink);
}
.state__tip {
  display: block;
  margin-top: 16rpx;
  font-size: 26rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.state__btn {
  display: inline-block;
  margin-top: 40rpx;
  padding: 20rpx 56rpx;
  border-radius: 24rpx;
  background: var(--sh-primary);
  color: #fff;
  font-size: 28rpx;
}
.hint {
  display: block;
  text-align: center;
  color: var(--sh-sub);
  font-size: 26rpx;
  padding: 28rpx 0;
}
.cm {
  background: var(--sh-surface);
  border-radius: 32rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
}
.cm__head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.cm__main {
  flex: 1;
  min-width: 0;
}
.cm__name {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.cm__addr {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
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
  display: flex;
  align-items: center;
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
.pk__main {
  flex: 1;
  min-width: 0;
}
.pk__name {
  display: block;
  font-size: 28rpx;
  color: var(--sh-ink);
  font-weight: 600;
}
.pk__sub {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
  margin-top: 6rpx;
}
.pk__dist {
  font-size: 24rpx;
  color: var(--sh-sub);
  flex-shrink: 0;
}
</style>
