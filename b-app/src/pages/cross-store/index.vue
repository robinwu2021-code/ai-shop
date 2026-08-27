<script setup lang="ts">
// 跨店对比（B-11.12.6 · 增值包 P2）。
//
// 这一页是增值包**真正卖的那样东西**：卖的不是「允许你开第二家店」（开两个主体
// 就能有，只是麻烦），是「一个手机管完，并且能横着比」。
//
// **原先这里还有一个「总览」tab**（各店今天几单、几个待办）。那份数字现在长在
// 门店管理的门店卡上 —— 想切店的人顺手就看见哪家忙。留着这个 tab，等于点进来
// 又能翻回刚看过的同一张列表。这一页只答后半个问题：这一段时间谁更好。
//
// ─────────────────────────────────────────────────────────────────────────────
// 三件在这一页上很容易做错的事
// ─────────────────────────────────────────────────────────────────────────────
// ① **评分是主体级的，绝不能画成每店一列**。`rvw_review` 只有 entity_no 没有
//    store_no，门店维度的评分没有数据源。塞进对比表里三家店会显示同一个数字，
//    而商家会把它当 bug 报上来（「你们的对比是不是坏了」）——
//    一个说不清来源的数字比没有这个数字更糟。所以它单独占顶部一行，并写明口径。
//
// ② **待办只有三项**（待发货/待自送/待备货）。工作台上还有待核销与待分拣，
//    但那两个数是**自提点**维度且不限商家（一个点承接多家商家的货，ADR-005）。
//    摆进「门店」这一列，商家会读成「这家店的活」，点进去却是别人的货。
//
// ③ **FREE 档看到的是示例态，不是错误页**。计划原文：「FREE 商家也要看得到这一页的
//    入口与样子（打码/示例态），否则他不知道自己缺什么」。入口照常显示，
//    点进来命中 70023 就渲染一份带「示例」标记的假数据 + 一句升档说明。
//    渲染成空白页或红色报错，商家读到的是「功能坏了」，而这本该是一次升档。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api, ApiError } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import { money } from "@shared/utils/money";
import type { CrossStoreCompare } from "@shared/types";
import { confirm } from "@ai-shop/ui/prompt";

/**
 * 能力位不足（后端 `ErrorCode.PLAN_CAPABILITY_REQUIRED`）。
 *
 * **它与 70006（角色不够）是两件事，解法也不同**：70006 去找老板加角色，
 * 这一个要升档。合成一种处理的话，FREE 档的老板会看到「让店主给你加个角色」，
 * 然后去找一个并不存在的开关。
 */
const PLAN_REQUIRED = 70023;

/** 可选窗口。只给 7 / 30 两档 —— 再多的选择对「哪家店最近更好」没有增量 */
const WINDOWS = [7, 30] as const;

const { t } = useI18n();
const merchant = useMerchantStore();

const days = ref<number>(30);
const compare = ref<CrossStoreCompare | null>(null);

/**
 * 命中 70023 —— **不是故障，是没买**。整页转示例态。
 *
 * 与 `failed` 严格分开：一个的下一步是「了解套餐」，另一个是「重试」。
 * 把两者合成一个 error 状态，等于让付费墙长得像 500。
 */
const locked = ref(false);
/** 后端给的拒绝文案（带当前档位）。比端上自己编一句更准 */
const lockedMsg = ref("");
/** 真的坏了（网络 / 500）。示例态不覆盖它 —— 那会把故障伪装成营销页 */
const failed = ref(false);

// ---------------------------------------------------------------- 示例数据
//
// **假数据只在 locked 时使用**，且每一处渲染都带「示例」标记 + 遮罩。
// 不带标记的假数据是最坏的一种：商家会当真，并据此做决定（比如以为分店在亏）。
// 三家店而不是两家：默认店、正常分店、停用店三种行各占一行，
// 这一页的全部形状都在里面。

function demoCompare(window: number): CrossStoreCompare {
  const k = window / 30;
  return {
    days: window,
    currency: "CNY",
    rating: 4.8,
    ratingCount: 126,
    stores: [
      {
        storeNo: "DEMO-1", storeName: String(t("crossStore.demo.s1")),
        isDefault: true, status: "ACTIVE",
        orders: Math.round(412 * k), gmvMinor: Math.round(2_356_000 * k),
        buyers: Math.round(233 * k), repeatBuyers: Math.round(96 * k),
        repeatRate: 0.41, outOfStockSkus: 2, rating: 4.9, ratingCount: 88,
      },
      {
        storeNo: "DEMO-2", storeName: String(t("crossStore.demo.s2")),
        isDefault: false, status: "ACTIVE",
        orders: Math.round(168 * k), gmvMinor: Math.round(903_500 * k),
        buyers: Math.round(121 * k), repeatBuyers: Math.round(27 * k),
        repeatRate: 0.22, outOfStockSkus: 7, rating: 4.5, ratingCount: 31,
      },
      {
        storeNo: "DEMO-3", storeName: String(t("crossStore.demo.s3")),
        isDefault: false, status: "READONLY",
        orders: Math.round(37 * k), gmvMinor: Math.round(186_400 * k),
        buyers: Math.round(31 * k), repeatBuyers: Math.round(4 * k),
        // 第三家刻意 0 条：示例态里也要能看到「暂无评价」那个形状
        repeatRate: 0.13, outOfStockSkus: 0, rating: 0, ratingCount: 0,
      },
    ],
  };
}

/** 画出来的那一份：锁着就是示例，否则是真数据 */
const shownCompare = computed(() =>
  locked.value ? demoCompare(days.value) : compare.value,
);

/** 对比里「最好的那家」加一个高亮 —— 横着比的时候，眼睛要有一个落点 */
const bestStoreNo = computed(() => {
  const rows = shownCompare.value?.stores ?? [];
  if (rows.length < 2) return "";
  return rows.reduce((best, r) => (r.gmvMinor > best.gmvMinor ? r : best), rows[0]!).storeNo;
});

function pct(v: number): string {
  return `${Math.round(v * 100)}%`;
}

// ---------------------------------------------------------------- 取数

async function loadCompare() {
  try {
    compare.value = await api.mCrossStoreCompare(days.value);
    locked.value = false;
  } catch (e) {
    applyError(e);
  }
}

function applyError(e: unknown) {
  if (e instanceof ApiError && e.code === PLAN_REQUIRED) {
    locked.value = true;
    lockedMsg.value = e.message;
    return;
  }
  // 70006 由传输层统一处理（重拉权限），这里只管「拿不到数」这一件事
  failed.value = true;
}

async function load() {
  await merchant.ensureProfile().catch(() => null);
  if (!merchant.canOperate) return;
  await merchant.ensureStores();
  /*
   * 判权状态要先到位。`can()` 在权限没加载时 fail-closed 返回 false，
   * 深链/刷新进来就永远不发请求，而且不会重试 —— 表现是一个永远空着的页面。
   */
  await merchant.ensureScope();
  // 门禁已经把整页挡掉了，这里不再发一个注定 70006 的请求
  if (!merchant.can("biz:customer")) return;
  failed.value = false;
  await loadCompare();
}

function pickWindow(d: number) {
  if (d === days.value) return;
  days.value = d;
  if (!locked.value) void loadCompare();
}

/**
 * 「了解套餐」。
 *
 * TODO(P4)：套餐页（`4.1 b-app 套餐页`）落地后指向 `ROUTES.plan`，
 * 并把「免费试用 14 天」放在这里（需求里意图最明确的一次）。
 * 一期没有那一页，也**没有自助购买**（§六 Q1 若定为运营手工授予就一直没有），
 * 所以这里只能说清「怎么开通」—— 给一个点了没反应的按钮比不给更糟。
 */
/**
 * 「了解套餐」→ 跳套餐页（增值包 P4 起）。
 *
 * <p>之前这里弹的是一句「怎么升档」的说明 —— 那是**套餐页还不存在时的替代品**。
 * 现在有了那一页：档位对比、用量、试用按钮都在那儿，而弹窗给不了这三样中的任何一样。
 *
 * <p>店长（无 `biz:store:admin`）过去会被那一页的 denied 拦住，所以对他仍然弹说明 ——
 * 跳过去看到「无权限」，比读一句「联系老板升档」更没用。
 */
function upgrade() {
  if (merchant.can("biz:store:admin")) {
    uni.navigateTo({ url: ROUTES.plan });
    return;
  }
  void confirm({
    title: String(t("crossStore.upgradeTitle")),
    hint: String(t("crossStore.upgradeHow")),
    alert: true,
  });
}

onShow(load);
</script>

<template>
  <!--
    经营数据属于客户资产（`biz:customer`），与 /biz/dashboard/stats 同一档。
    ⚠️ 这道门与能力位是**两道正交的门**：这里管「这个人能不能看」，
    70023 管「这家店买没买」。两者不能合并，解法不一样。
  -->
  <sh-scaffold title-key="crossStore.title" :denied="!merchant.can('biz:customer')">
    <text class="txt-display">{{ $t("crossStore.title") }}</text>
    <text class="sh-muted sub">{{ $t("crossStore.subtitle") }}</text>

    <!--
      付费墙横幅。**在 tab 上面、内容之前** —— 商家先看到「这是示例」，
      再往下看那些数字，顺序反了就会先把假数据当真。
    -->
    <view v-if="locked" class="lock">
      <view class="lock__row">
        <text class="lock__tag">{{ $t("crossStore.demoTag") }}</text>
        <text class="lock__t">{{ $t("crossStore.lockTitle") }}</text>
      </view>
      <text class="lock__d">{{ lockedMsg || $t("crossStore.lockHint") }}</text>
      <text class="lock__d">{{ $t("crossStore.lockUpsell") }}</text>
      <view class="sh-btn lock__go" @tap="upgrade">{{ $t("crossStore.learnPlan") }}</view>
    </view>

    <!-- 真的拿不到数（网络/500）：这是故障，给重试，不给营销 -->
    <view v-if="failed && !locked" class="sh-card fail">
      <text class="txt-title">{{ $t("crossStore.failed") }}</text>
      <view class="sh-btn fail__go" @tap="load">{{ $t("common.retry") }}</view>
    </view>

    <!-- ---------------------------------------------------------- 对比 -->
    <template v-if="shownCompare">
      <!--
        ★ 主体评分单独一行，**不进下面的对比卡片**。
        `rvw_review` 只有 entity_no —— 按店给会让三家店显示同一个数。
        这一行必须把口径写在旁边，否则它看着像「默认店的评分」。
      -->
      <view class="sh-card rating">
        <view class="rating__row">
          <text class="txt-title">{{ $t("crossStore.rating") }}</text>
          <template v-if="shownCompare.ratingCount">
            <sh-rating :value="shownCompare.rating"></sh-rating>
          </template>
          <text v-else class="sh-muted">{{ $t("crossStore.noRating") }}</text>
        </view>
        <text class="sh-muted rating__hint">
          {{ $t("crossStore.ratingScope", { n: shownCompare.ratingCount }) }}
        </text>
      </view>

      <!-- 窗口切换。回显后端夹取后的实际天数，不照着自己发的值写 -->
      <view class="windows">
        <text
          v-for="d in WINDOWS"
          :key="d"
          class="sh-chip windows__i"
          :class="{ 'sh-chip--primary': shownCompare.days === d }"
          @tap="pickWindow(d)"
        >
          {{ $t("crossStore.lastDays", { n: d }) }}
        </text>
      </view>

      <view
        v-for="s in shownCompare.stores"
        :key="s.storeNo"
        class="sh-card store"
        :class="{ 'is-demo': locked, 'is-best': s.storeNo === bestStoreNo }"
      >
        <view class="store__head">
          <text class="store__name">{{ s.storeName }}</text>
          <text v-if="s.isDefault" class="sh-chip tag">{{ $t("crossStore.default") }}</text>
          <text v-if="s.status !== 'ACTIVE'" class="sh-chip tag">
            {{ $t("crossStore.disabled") }}
          </text>
          <text v-if="s.storeNo === bestStoreNo" class="sh-chip sh-chip--solid tag">
            {{ $t("crossStore.best") }}
          </text>
          <text v-if="locked" class="sh-chip tag tag--demo">{{ $t("crossStore.demoTag") }}</text>
        </view>

        <view class="metrics">
          <view class="metrics__i">
            <text class="metrics__v sh-num">{{ money(s.gmvMinor, shownCompare.currency) }}</text>
            <text class="sh-muted">{{ $t("crossStore.gmv") }}</text>
          </view>
          <view class="metrics__i">
            <text class="metrics__v sh-num">{{ s.orders }}</text>
            <text class="sh-muted">{{ $t("crossStore.orders") }}</text>
          </view>
          <view class="metrics__i">
            <text class="metrics__v sh-num">{{ pct(s.repeatRate) }}</text>
            <text class="sh-muted">{{ $t("crossStore.repeatRate") }}</text>
          </view>
          <view class="metrics__i">
            <text class="metrics__v sh-num" :class="{ 'is-warn': s.outOfStockSkus > 0 }">
              {{ s.outOfStockSkus }}
            </text>
            <text class="sh-muted">{{ $t("crossStore.outOfStock") }}</text>
          </view>
          <!--
            ★ 门店评分（V155）。**按条数判空**，不按分值 ——
            还没人评过的店后端给的是中位分（只影响排序），显示成星等于凭空给它一个口碑。
          -->
          <view class="metrics__i">
            <text class="metrics__v sh-num">
              {{ s.ratingCount ? s.rating.toFixed(1) : "—" }}
            </text>
            <text class="sh-muted">{{ $t("crossStore.storeRating") }}</text>
          </view>
        </view>

        <!-- 复购率的分子分母摆出来：一家只有 3 个买家的店，33% 不代表什么 -->
        <text class="sh-muted note note--tight">
          {{ $t("crossStore.repeatBasis", { r: s.repeatBuyers, b: s.buyers }) }}
        </text>
      </view>

      <text class="sh-muted note">{{ $t("crossStore.oosNote") }}</text>
      <text class="sh-muted note">{{ $t("crossStore.legacyNote") }}</text>
    </template>

    <!-- 拿到了但一家店都没有（授权被收回时会这样）：说清楚，别画一张空表 -->
    <sh-empty
      v-if="!failed && !locked && compare && !compare.stores.length"
      :text="String($t('crossStore.noStores'))"
    ></sh-empty>
  </sh-scaffold>
</template>

<style scoped>
.sub {
  display: block;
  margin: 8rpx 0 24rpx;
  line-height: 1.6;
}
/* 付费墙：用主色浅底而不是警示红 —— 这不是故障，是一次升档邀请 */
.lock {
  padding: 24rpx;
  border-radius: 32rpx;
  background: var(--sh-primary-tint);
  margin-bottom: 16rpx;
}
.lock__row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.lock__tag {
  flex-shrink: 0;
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
}
.lock__t {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.lock__d {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
  color: var(--sh-sub);
}
.lock__go {
  margin-top: 20rpx;
}
.fail {
  text-align: center;
}
.fail__go {
  margin-top: 20rpx;
}
.store {
  margin-bottom: 14rpx;
}
/*
 * 示例态的遮罩：**压低对比度而不是模糊**。
 * filter: blur() 在小程序端各基础库表现不一，而这里要的效果只是
 * 「看得出形状、看得出这不是你的数」。数字仍可读是刻意的 ——
 * 全糊掉就看不出这一页能给他什么了，而那正是这一屏存在的唯一理由。
 */
.store.is-demo {
  opacity: 0.62;
  border: 2rpx dashed var(--sh-line);
}
.store.is-best {
  border: 2rpx solid var(--sh-primary);
}
.store__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12rpx;
}
.store__name {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.tag {
  font-size: 24rpx;
  padding: 4rpx 14rpx;
}
/*
 * 演示数据的标记：**灰底，不是虚线药丸**。
 *
 * 虚线药丸在这套界面里有确定的意思 —— 「候选：点一下就加进来」
 *（见 base.css 的 .sh-chip--dashed）。拿它标「这是演示数据」是
 * **同一个视觉两个意思**：商家看到虚线会以为点一下能把这家店加进来。
 *
 * 演示数据也不是警告（那是 --warning 那一档），它只是「这不是真的」——
 * 最轻的一档灰底 chip 就够，所以这里只声明一条：不要主色。
 */
.tag--demo {
  opacity: 0.7;
}
.grid {
  display: flex;
  margin-top: 16rpx;
}
.grid__i {
  flex: 1;
}
.grid__v {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  color: var(--sh-ink);
  line-height: 1.2;
}
.month {
  margin-top: 16rpx;
}
.todo {
  display: flex;
  margin-top: 16rpx;
  padding-top: 20rpx;
  border-top: var(--sh-hairline-soft);
}
.todo__i {
  flex: 1;
  text-align: center;
}
.todo__v {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.todo__v.is-zero {
  color: var(--sh-faint);
}
.todo__l {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.rating {
  margin-bottom: 16rpx;
}
.rating__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rating__hint {
  display: block;
  margin-top: 12rpx;
  line-height: 1.6;
}
.windows {
  display: flex;
  gap: 12rpx;
  margin-bottom: 16rpx;
}
.windows__i {
  font-size: 24rpx;
  padding: 12rpx 24rpx;
}
.metrics {
  display: flex;
  flex-wrap: wrap;
  margin-top: 20rpx;
}
.metrics__i {
  flex: 1 1 50%;
  margin-top: 16rpx;
}
.metrics__v {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  color: var(--sh-ink);
  line-height: 1.3;
}
.metrics__v.is-warn {
  color: var(--sh-danger);
}
.note {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  line-height: 1.6;
}
.note--tight {
  margin-top: 16rpx;
}
</style>
