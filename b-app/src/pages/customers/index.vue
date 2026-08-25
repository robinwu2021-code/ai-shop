<script setup lang="ts">
// 会员（P1）。**路由沿用 pages/customers** —— 它是「我的客户」那一页的升级版，
// 换路由只会让存量深链失效，而这一页本来就从「我的」进。
//
// 平台电商给商家看的是「流量、转化率、UV」；小店老板要的是另一种东西：
// **张阿姨上个月每周都来，这半个月没来了**。所以这一页只回答三个问题：
// 有多少人、谁快流失了、这个人是谁带来的。没有图表，没有漏斗。
//
// ⚠️ 隐私：只给手机号后四位（B12）。按号找人**必须输完整号** ——
// 前缀模糊查询会把会员库变成一本通讯录，输「138」就能翻出一屏人。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { Member, MemberStats } from "@shared/types";

const { t } = useI18n();
const merchant = useMerchantStore();

const stats = ref<MemberStats | null>(null);
const list = ref<Member[]>([]);
const loading = ref(false);

/** 四层之一，或空 = 全部。点顶部那四个数字就是在切它 */
const level = ref("");
/** 门店筛选。多店主体才出现；空 = 全部门店 */
const storeNo = ref("");
const phone = ref("");
/** 输了号但不足 11 位时给一句解释 —— 否则他会以为「这个人不见了」 */
const phonePartial = computed(() => phone.value.length > 0 && phone.value.length < 11);

const LEVELS = ["NEW", "REGULAR", "LOYAL", "SLEEPING"] as const;

function countOf(lv: string) {
  const s = stats.value;
  if (!s) return 0;
  if (lv === "NEW") return s.newCount;
  if (lv === "REGULAR") return s.regularCount;
  if (lv === "LOYAL") return s.loyalCount;
  return s.sleepingCount;
}

async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const [s, page] = await Promise.all([
      api.mMemberStats(storeNo.value || undefined),
      api.mMembers({
        storeNo: storeNo.value || undefined,
        level: level.value || undefined,
        phone: phone.value.length >= 11 ? phone.value : undefined,
        page: 1,
        size: 50,
      }),
    ]);
    stats.value = s;
    list.value = page.records;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

function pickLevel(v: string) {
  level.value = level.value === v ? "" : v;
  void load();
}

function pickStore() {
  const stores = merchant.stores;
  const items = [String(t("members.allStores")), ...stores.map((s) => s.name || s.storeNo)];
  uni.showActionSheet({
    itemList: items,
    success: (r) => {
      storeNo.value = r.tapIndex === 0 ? "" : stores[r.tapIndex - 1]?.storeNo ?? "";
      void load();
    },
  });
}

function storeName(no: string) {
  return merchant.stores.find((s) => s.storeNo === no)?.name || no;
}

function open(m: Member) {
  uni.navigateTo({ url: `/pages/member-detail/index?memberNo=${m.memberNo}` });
}

/** 沉睡用警示色：那是店主唯一能立刻行动的一批 */
function levelClass(lv?: string | null) {
  return lv === "SLEEPING" ? "sh-chip--warning" : "sh-chip--primary";
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="members.title" :denied="!merchant.can('biz:customer')">
    <!-- 多店主体才有门店切换。按门店经营时这里必须选一家，否则数字与列表口径对不上 -->
    <view v-if="merchant.multiStore" class="bar">
      <text class="sh-chip sh-chip--primary" @tap="pickStore">
        {{ storeNo ? storeName(storeNo) : $t("members.allStores") }} ▾
      </text>
    </view>

    <!-- 四层数字即入口：点一个就按那一层筛，再点一下取消 -->
    <view class="quad">
      <view
        v-for="lv in LEVELS"
        :key="lv"
        class="quad__i"
        :class="{ 'is-on': level === lv }"
        @tap="pickLevel(lv)"
      >
        <text class="quad__n sh-num" :class="{ 'is-warn': lv === 'SLEEPING' }">{{ countOf(lv) }}</text>
        <text class="quad__l">{{ $t(`members.level.${lv}`) }}</text>
      </view>
    </view>

    <text v-if="stats" class="sh-muted sub">
      {{ $t("members.summary", { n: stats.newThisMonth, m: stats.reachable }) }}
    </text>

    <!--
      未计入的买家。**先说，比等他问强** ——
      商家一定会拿订单数与会员数对，对不上时他的第一反应是数据丢了。
    -->
    <view v-if="stats && stats.unlinkedBuyers > 0" class="notice">
      {{ $t("members.unlinked", { n: stats.unlinkedBuyers }) }}
    </view>

    <view class="search">
      <input
        v-model="phone"
        class="field__input"
        type="number"
        maxlength="11"
        :placeholder="$t('members.phonePh')"
        @confirm="load"
      />
    </view>
    <text v-if="phonePartial" class="sh-muted hint">{{ $t("members.phonePartial") }}</text>

    <sh-empty v-if="!list.length && !loading" :text="String($t('members.empty'))"></sh-empty>

    <view v-for="m in list" :key="m.memberNo" class="sh-card row" @tap="open(m)">
      <view class="row__main">
        <view class="row__head">
          <text class="row__name">···{{ m.phoneTail || "----" }}</text>
          <text v-if="m.level" class="sh-chip" :class="levelClass(m.level)">
            {{ $t(`members.level.${m.level}`) }}
          </text>
          <text v-if="m.status === 'LEAD'" class="sh-chip">{{ $t("members.lead") }}</text>
        </view>
        <text class="sh-muted sh-num">
          {{ $t("members.stat", { n: m.orderCount, m: money(m.totalSpentMinor) }) }}
        </text>
        <text class="sh-muted">
          {{ $t(`members.source.${m.source}`) }}
          <template v-if="m.daysSinceLast != null">
            · {{ m.daysSinceLast === 0 ? $t("members.today")
              : $t("members.daysAgo", { n: m.daysSinceLast }) }}
          </template>
        </text>
      </view>
      <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
    </view>

    <text v-if="list.length" class="tip">{{ $t("members.privacyHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.bar {
  margin-bottom: 12rpx;
}
/* 四格数字：点得动，所以要有可点的样子（选中时主色底） */
.quad {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12rpx;
}
.quad__i {
  background: var(--sh-card);
  border-radius: 24rpx;
  padding: 20rpx 8rpx;
  text-align: center;
}
.quad__i.is-on {
  background: var(--sh-primary-tint);
}
.quad__n {
  display: block;
  font-size: 40rpx;
  font-weight: 600;
  line-height: 1.2;
  color: var(--sh-ink);
}
.quad__n.is-warn {
  color: var(--sh-primary);
}
.quad__l {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.sub {
  display: block;
  margin: 16rpx 0 0;
  font-size: 24rpx;
}
/* 未计入提示：主色浅底，不是警示红 —— 这不是故障，是一个需要解释的差额 */
.notice {
  margin-top: 12rpx;
  padding: 16rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 24rpx;
  line-height: 1.5;
}
.search {
  margin-top: 16rpx;
}
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 14rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__head {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 6rpx;
}
.row__name {
  font-size: 28rpx;
  font-weight: 600;
}
/* `<text>` 默认 inline —— 不给 block，「6 单 · ¥272」与「下过单 · 今天」会挤成一行 */
.row__main .sh-muted {
  display: block;
  line-height: 1.6;
}
.tip {
  display: block;
  margin-top: 24rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
