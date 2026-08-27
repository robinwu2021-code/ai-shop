<script setup lang="ts">
// 我发起的团 —— 邻里自提的发起人侧（C-FF-09/10）。
//
// 这页存在的理由：求团买床垫、校服这类东西**没有门店可提**，只能送到发起人家里，
// 由他签收、由他把货交给各家邻居。所以发起人需要一个最小的「签收 + 核销」能力。
//
// 三条硬约束（都在服务侧强制，不靠页面自觉）：
//   1. **零报酬** —— 承接的邻居一旦有收益，他就是团长，ADR-004 消掉的合规问题会全部回来
//   2. **作用域限本团** —— 拿到别的团的码也核不掉，这跟商家履约台是两套权限
//   3. **只能是自己家** —— 不能指定别人家，那是替他人分配义务
//
// 签收不等于放弃售后：整批签收后个别缺损照常走售后流程。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { ROUTES } from "@shared/utils/constants";
import type { GroupBuy, GroupPickupOrder } from "@shared/types";

const { t } = useI18n();

const groups = ref<GroupBuy[]>([]);
const active = ref("");
const orders = ref<GroupPickupOrder[]>([]);
const code = ref("");
const error = ref("");
const busy = ref(false);

/** 只有「送到我家」的团才需要发起人履约 —— 到店自提的团由商家核销 */
const hosting = computed(() => groups.value.filter((g) => g.neighborPickup));
const current = computed(() => hosting.value.find((g) => g.groupNo === active.value));
const waiting = computed(() => orders.value.filter((o) => o.status === "FULFILLING"));
const preparing = computed(() => orders.value.filter((o) => o.status === "PAID"));

async function load() {
  error.value = "";
  groups.value = await api.myHostedGroups();
  if (!active.value && hosting.value[0]) active.value = hosting.value[0].groupNo;
  if (active.value) orders.value = await api.groupPickupOrders(active.value);
}

async function pick(groupNo: string) {
  active.value = groupNo;
  orders.value = await api.groupPickupOrders(groupNo);
}

async function receive() {
  if (!current.value || busy.value) return;
  busy.value = true;
  try {
    // 签收前还在途的这些，签收后就变成「待取」—— 数在调用前算，
    // 因为接口返回的是团本身（后端一直如此），不是被改动的订单列表
    const n = preparing.value.length;
    await api.confirmGroupBatch(current.value.groupNo);
    uni.showToast({ title: t("groupHost.received", { n }), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

async function verify(input?: string) {
  const c = (input ?? code.value).trim();
  if (!c || !current.value || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    await api.verifyGroupPickup(current.value.groupNo, c);
    code.value = "";
    uni.showToast({ title: t("groupHost.done"), icon: "none" });
    await pick(current.value.groupNo);
  } catch (e) {
    // 失败原因要说清楚：不属于本团 / 已核销 / 码无效，处理方式完全不同
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

function gotoGroup(groupNo: string) {
  uni.navigateTo({ url: `${ROUTES.group}?groupNo=${groupNo}` });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="groupHost.title">
    <text class="sh-h1">{{ $t("groupHost.title") }}</text>

    <sh-empty v-if="!hosting.length" :text='$t("groupHost.empty")'></sh-empty>

    <template v-else>
      <!-- 多个团时切换 -->
      <!-- 此前这里把 `sh-tabs` 手画了一遍：同样是一排 `sh-chip`、选中挂
           `sh-chip--primary`。组件多做一件事 —— 超过四项自动横滚，
           而手画那版一多就换行、把下面的内容顶下去。 -->
      <sh-tabs
        v-if="hosting.length > 1"
        class="tabs"
        :items="hosting.map((g) => ({ key: g.groupNo, label: g.title }))"
        :active="active"
        @change="pick"
      ></sh-tabs>

      <view v-if="current" class="sh-card info">
        <view class="info__row" @tap="gotoGroup(current.groupNo)">
          <text class="info__title">{{ current.title }}</text>
          <text class="sh-chip" :class="current.reached ? 'sh-chip--primary' : 'sh-chip--warning'">
            {{ current.reached ? $t("groupHost.reached") : $t("groupHost.need", { n: current.need }) }}
          </text>
        </view>
        <text class="sh-muted addr">
          {{ current.neighborPickup?.name }} · {{ current.neighborPickup?.address }}
        </text>
        <text class="sh-muted">
          {{ $t("groupHost.slot") }}{{ current.neighborPickup?.timeSlot }}
        </text>
        <text class="free">{{ $t("groupHost.freeHint") }}</text>
      </view>

      <!-- 批次签收：整批到货后点一次，参团邻居收到通知 -->
      <view v-if="preparing.length" class="sh-btn receive" @tap="receive">
        {{ $t("groupHost.receive", { n: preparing.length }) }}
      </view>

      <!-- 轻核销：邻居来取货时逐单核掉 -->
      <view class="sh-card verify">
        <text class="sh-h2">{{ $t("groupHost.verify") }}</text>
        <view class="sh-row sh-mt-sm">
          <input
            maxlength="16"
            v-model="code"
            class="field__input sh-num"
            :placeholder="$t('groupHost.codePh')"
            confirm-type="done"
            @confirm="verify()"
          />
          <text class="btn" @tap="verify()">{{ $t("groupHost.doVerify") }}</text>
        </view>
        <text v-if="error" class="err">{{ error }}</text>
      </view>

      <view class="list-head">
        <text class="sh-h2">{{ $t("groupHost.waiting") }}</text>
        <text class="sh-muted sh-num">{{ waiting.length }}</text>
      </view>

      <sh-empty v-if="!waiting.length" compact :text='$t("groupHost.noWaiting")'></sh-empty>

      <view v-for="o in waiting" :key="o.subOrderNo" class="sh-card row-item">
        <view class="sh-fill">
          <text class="row-item__code sh-num">{{ o.verifyCode }}</text>
          <text class="sh-muted">{{ o.buyerNickname || "—" }} · {{ o.items.length }} 件</text>
        </view>
        <text class="btn" @tap="verify(o.verifyCode)">{{ $t("groupHost.doVerify") }}</text>
      </view>

      <text class="tip sh-hint">{{ $t("groupHost.afterSaleHint") }}</text>
    </template>
  </sh-scaffold>
</template>

<style scoped>
.empty.small {
  padding: 48rpx 0;
}
/* 排布归 `sh-tabs`，这里只留这一段的上下留白 */
.tabs {
  margin: 24rpx 0;
}
.info {
  margin-top: 24rpx;
}
.info__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.info__title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.addr {
  display: block;
  margin-top: 12rpx;
}
.free {
  display: block;
  margin-top: 16rpx;
  padding: 16rpx 20rpx;
  border-radius: 24rpx;
  background: var(--sh-primary-tint);
  color: var(--sh-primary-text);
  font-size: 24rpx;
  line-height: 1.6;
}
.receive {
  margin-top: 24rpx;
}
.verify {
  margin-top: 24rpx;
}

/* 这一页特有的两条：与旁边的按钮同行分宽，以及验证码的字距。
   其余（高度 / 圆角 / 底色 / 字号）都由 `.field__input` 给 */
.field__input {
  flex: 1;
  letter-spacing: 4rpx;
}
.btn {
  padding: 20rpx 30rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 26rpx;
  font-weight: 600;
}
.err {
  display: block;
  margin-top: 20rpx;
  padding: 18rpx 22rpx;
  border-radius: 24rpx;
  background: var(--sh-danger-tint);
  color: var(--sh-danger);
  font-size: 24rpx;
}
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 32rpx 8rpx 16rpx;
}
.row-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 16rpx;
}

.row-item__code {
  display: block;
  font-size: 34rpx;
  font-weight: 600;
  letter-spacing: 4rpx;
  color: var(--sh-ink);
}
.tip {
  margin: 32rpx 8rpx;
}
</style>
