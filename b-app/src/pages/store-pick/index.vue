<script setup lang="ts">
/**
 * 选择门店。**唯一的切店入口**（进 App 时多店主体先到这里；之后从「我的」进来）。
 *
 * 只有一家能进的店时这一页不该出现 —— 调用方（App 启动、登录）按
 * `merchant.needsStorePick` 判；这里不再判，否则直接打开 URL 的人会被静默弹走。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { useI18n } from "vue-i18n";
import type { EntityStores } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();
/** 进 App 的那一次：选完去工作台；从「我的」进来的：选完回上一页 */
const entry = ref(false);
const picked = ref("");
/** 正在切店：亮着「切换中…」时挡住重复点「进入」 */
const switching = ref(false);

/**
 * 按证照分组。**选一家门店同时定了两件事**：用哪张证照、进哪家店 ——
 * 这也是切证照的唯一入口（产品方案 §2.1：日常打交道的是门店，不是证照）。
 *
 * 拿不到分组（老后端、或这次请求失败）时退回单组：把 `merchant.stores` 当成
 * 唯一一张证照的门店。**宁可少一个分组头，也不要整页空白** ——
 * 他打开这一页是为了进店干活。
 */
const groups = computed<EntityStores[]>(() => {
  if (merchant.entityGroups.length) return merchant.entityGroups;
  return merchant.stores.length
    ? [{ entity: null as never, stores: merchant.stores }]
    : [];
});
/** 单证照时整个不画分组头 —— 只有一组的分组是纯噪音 */
const grouped = computed(() => merchant.multiEntity);
const current = computed(() =>
  groups.value.flatMap((g) => g.stores).find((s) => s.storeNo === picked.value));

onLoad(async (q) => {
  entry.value = q?.entry === "1";
  /*
   * **每次都重取，不是 ensure**（与门店管理页同一条理由，而这里更强）：
   * 这一页是切证照的唯一入口，拿旧分组的代价是「选完进了另一张证照的店」。
   *
   * `ensure*` 的判据都盖不住证照被合并/停用这件事：`entityGroups` 判的是「非空」，
   * `ensureStores` 判的是「当前门店在不在列表里」—— 两张证照并成一张时**门店集合根本没变**，
   * 两个判据都说「新鲜」，于是整段会话都停在合并之前的分组上。
   * App 进程比一次性加载活得久，H5 刷一下就好，真机上是一直错到杀进程为止。
   */
  await Promise.all([merchant.loadStores(), merchant.loadEntityGroups()]);
  picked.value = merchant.storeNo || merchant.usableStores[0]?.storeNo || "";
});

/** 证照状态 → 那一组标题右边的小字。营业中不出字：没问题的东西不该占视线 */
function entityNote(g: EntityStores): string {
  const st = g.entity?.status;
  if (st === "PENDING_LICENSE") return t("storePick.entityPending");
  if (st && st !== "ACTIVE") return t("storePick.entityClosed");
  return "";
}

function choose(storeNo: string, status: string) {
  if (status !== "ACTIVE") return;
  picked.value = storeNo;
}

async function confirm() {
  if (!picked.value || switching.value) return;
  /*
   * **切店要让人看见过程，也要让人落到已经切过去的那一屏。**
   *
   * 此前这里 `pickStore` 一调就立刻 reLaunch —— 而切店的异步工作（loadScope，
   * 跨证照时还有门店列表与资料重拉）那时还没完成，落地页拿旧店数据先渲染一帧，
   * 店主的感受是「点了，但好像没换过去」。
   *
   * 现在：先亮「切换中…」（mask 挡住重复点），await 到真的切完，再落地 ——
   * 落地那一屏（工作台标题「工作台 · 新店名」/「我的」头部）已经是新店，
   * 那就是「切过去了」最直接的回馈。
   */
  switching.value = true;
  uni.showLoading({ title: String(t("storePick.switching")), mask: true });
  try {
    await merchant.pickStore(picked.value);
  } finally {
    uni.hideLoading();
    switching.value = false;
  }
  if (entry.value) {
    // reLaunch：这一页不该留在栈里，返回键不应回到「选择门店」
    uni.reLaunch({ url: ROUTES.home });
  } else {
    uni.navigateBack();
  }
}
</script>

<template>
  <sh-scaffold title-key="storePick.title">
    <text class="txt-display">{{ $t("storePick.heading") }}</text>
    <text class="sh-hint">{{ $t("storePick.hint") }}</text>

    <view v-for="g in groups" :key="g.entity?.entityNo || 'only'" class="list">
      <!-- 分组头只在多证照时出现 -->
      <view v-if="grouped" class="group sh-row sh-row--baseline">
        <text class="txt-strong group__name txt-quiet">{{ g.entity?.name }}</text>
        <text v-if="entityNote(g)" class="txt-caption is-warning">{{ entityNote(g) }}</text>
      </view>
      <view
        v-for="s in g.stores"
        :key="s.storeNo"
        class="sh-row sh-card item"
        :class="{ 'is-on': s.storeNo === picked, 'is-off': s.status !== 'ACTIVE' }"
        @tap="choose(s.storeNo, s.status)"
      >
        <view class="sh-fill">
          <text class="txt-strong item__name">
            {{ s.name }}<text v-if="s.isDefault" class="sh-chip item__chip">{{ $t("storePick.default") }}</text>
          </text>
          <text class="txt-caption item__sub">
            <template v-if="s.status !== 'ACTIVE'">{{ $t("storePick.closed") }}</template>
            <template v-else>
              {{ s.address || "—" }}<template v-if="s.storeNo === merchant.storeNo && merchant.storePicked"> · {{ $t("storePick.last") }}</template>
            </template>
          </text>
        </view>
        <sh-check round :model-value="s.storeNo === picked"></sh-check>
      </view>
    </view>

    <view class="sh-btn enter" :class="{ 'is-disabled': !current }" @tap="confirm">
      {{ $t("storePick.enter") }}
    </view>
    <text class="center sh-hint">{{ $t("storePick.crossHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint.center {
  text-align: center;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.group {
  margin-top: 8rpx;
}
/* ⚠️ 此前写的是 `var(--sh-warn, var(--sh-sub))` —— **`--sh-warn` 这个变量不存在**
   （正名是 `--sh-warning`），于是这行字永远走兜底、渲染成普通灰。
   它本该是一句提醒（这一组里有店打烊了），灰下去就跟旁边的说明文字一样重。
   皮肤变量守卫故意放行带兜底的写法（「拼错了也还有兜底」），
   而这恰恰是它看不见的那一类：**兜底把拼错的后果盖住了**。 */
.item {
  gap: 24rpx;
  border: 4rpx solid transparent;
}
.item.is-on {
  border-color: var(--sh-primary);
}
.item.is-off {
  opacity: 0.55;
}

.item__name {
  display: block;
}
.item__chip {
  margin-inline-start: 12rpx;
}
.item__sub {
  display: block;
  margin-top: 4rpx;
}

.enter.is-disabled {
  opacity: 0.5;
}
</style>
