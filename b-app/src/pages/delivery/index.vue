<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 商家自送（B-11.4.6 / 4.7）。
//
// ⚠️ **不做骑手系统**（ADR-005 §5）：小店老板骑电动车送两条街，他要的是「点一下已送达」，
// 不是位置回传和轨迹回放。这一条做重了店主不会用，送货上门这条线就是废的。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money, toMajor, toMinor } from "@shared/utils/money";
import { FULFILLMENT } from "@shared/utils/constants";
import type { DeliveryRule, Order } from "@shared/types";

const { t } = useI18n();

/**
 * 配送规则。**只有能改门店经营面的人才拿得到**（`/biz/delivery/rule` 要 `biz:store`）。
 *
 * 拿不到时是 `null` 而不是那份默认值 —— 默认值会让店员看到一张
 * 「半径 3000 米、起送 0 元」的规则卡，那是**编出来的**：他没权限读，
 * 屏幕上却显示了一个具体数字，而店里真实的规则可能完全不同。
 */
const rule = ref<DeliveryRule | null>(null);
/** 我能不能改配送规则 —— 决定规则卡片画不画 */
const canRule = computed(() => merchant.can("biz:store"));
/** 表单用主单位（元），保存时换回最小单位 —— 店主输 20，存 2000 */
const form = ref({ radius: "3000", minOrder: "0", fee: "0", freeThreshold: "0" });
const orders = ref<Order[]>([]);
const busy = ref("");

const pending = computed(() =>
  orders.value.filter((o) => o.fulfillment === FULFILLMENT.DELIVERY && o.status === "PAID"),
);

/*
 * 两件事各自取，**不用裸 Promise.all**。
 *
 * 这一页的门禁是 `biz:ship`，而规则接口要的是 `biz:store` —— 店员与配送员
 * 有前者没有后者。原先一个 Promise.all 把两件事绑在一起，规则被 70006 拒
 * 就整体 reject，**待送列表也一起没了**：配送员打开为他而设的页面，看到的是一片空白。
 *
 * 而工作台「待配送」格子的权限正是 `biz:ship`，它每天都在把配送员往这儿送。
 */
async function load() {
  /*
   * **先等权限到位**。`can()` 在 perms 没加载时一律 false（fail-closed），
   * 而深链进来时 `onShow` 会早于外壳的 `ensureScope` 跑完 ——
   * 不等的话老板刷新这一页也看不到规则卡，且**不会重试**：
   * 那正是「判权状态没加载 = 界面被自己锁死」这个老问题的新形态。
   */
  await merchant.ensureScope();
  const [r, res] = await Promise.all([
    canRule.value ? api.mDeliveryRule().catch(() => null) : Promise.resolve(null),
    api.mOrderList({ size: 100 }).catch(() => null),
  ]);
  rule.value = r;
  if (r) {
    form.value = {
      radius: String(r.radius),
      minOrder: toMajor(r.minOrderMinor),
      fee: toMajor(r.feeMinor),
      freeThreshold: toMajor(r.freeThresholdMinor),
    };
  }
  orders.value = res?.records ?? [];
}

async function saveRule() {
  rule.value = await api.mSaveDeliveryRule({
    radius: Number(form.value.radius) || 0,
    minOrderMinor: toMinor(form.value.minOrder),
    feeMinor: toMinor(form.value.fee),
    freeThresholdMinor: toMinor(form.value.freeThreshold),
  });
  uni.showToast({ title: t("common.saved"), icon: "none" });
}

/**
 * 拨号。**脱敏号拨不通，所以只在拿到完整号时才让点** ——
 * 一个点了没反应的电话号码比不显示更糟。
 */
function call(o: Order) {
  const phone = o.receiver?.phone;
  if (!phone || phone.includes("*")) return;
  uni.makePhoneCall({ phoneNumber: phone });
}

async function delivered(o: Order) {
  if (busy.value) return;
  busy.value = o.orderNo;
  try {
    await api.mDelivered(o.orderNo);
    uni.showToast({ title: t("order.deliveredDone"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = "";
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="delivery.title" :denied="!merchant.can('biz:ship')">
    <text class="txt-display">{{ $t("delivery.title") }}</text>

    <!--
      规则卡片只给能改门店经营面的人（`biz:store`）。店员与配送员进得来这一页
      （他们有 `biz:ship`），但读不到规则 —— 画一张空表格让他填、点保存报 70006，
      比不画它更糟。
    -->
    <view v-if="canRule && rule" class="sh-card sh-mt-sm">
      <text class="txt-title">{{ $t("delivery.rule") }}</text>

      <view class="field">
        <text class="field__label">{{ $t("delivery.radius") }}</text>
        <input maxlength="3" v-model="form.radius" class="field__input sh-num" type="number" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.minOrder") }}</text>
        <input maxlength="10" v-model="form.minOrder" class="field__input sh-num" type="digit" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.fee") }}</text>
        <input maxlength="10" v-model="form.fee" class="field__input sh-num" type="digit" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("delivery.freeThreshold") }}</text>
        <input maxlength="10" v-model="form.freeThreshold" class="field__input sh-num" type="digit" />
        <text class="hint">{{ $t("delivery.freeHint") }}</text>
      </view>

      <view class="sh-btn sh-btn--soft save" @tap="saveRule">{{ $t("common.save") }}</view>
    </view>

    <view class="list-head">
      <text class="txt-title">{{ $t("delivery.pending") }}</text>
      <text class="sh-muted sh-num">{{ pending.length }}</text>
    </view>

    <sh-empty v-if="!pending.length" :text='$t("delivery.empty")'></sh-empty>

    <view v-for="o in pending" :key="o.orderNo" class="sh-row sh-card row sh-mb-sm">
      <view class="row__main">
        <!--
          **送到哪里、找谁、打哪个号** —— 这一页在这之前只有单号和金额，
          配送员拿着它出不了门。自送单的手机号后端给的是完整号（其余履约方式脱敏），
          点一下直接拨：站在楼下找不到人时，多一步操作就是多一次白跑。
        -->
        <text class="row__buyer">{{ o.receiver?.name || o.buyerNickname || "—" }}</text>
        <text v-if="o.receiver?.address" class="row__addr">{{ o.receiver.address }}</text>
        <text v-else class="row__addr row__addr--none">{{ $t("delivery.noAddress") }}</text>
        <view class="row__sub">
          <text class="sh-muted sh-num">{{ o.orderNo }}</text>
          <text v-if="o.receiver?.phone" class="row__tel sh-num" @tap="call(o)">
            {{ o.receiver.phone }}
          </text>
        </view>
      </view>
      <!--
        配送员拿到的是**裁剪档**（后端 CourierOrderVO，无金额、无核销码）。
        所以这里按字段有无渲染，而不是按角色判 —— 少一处判断就少一处会漂的地方。
      -->
      <text v-if="o.amount" class="row__amount sh-num">
        {{ money(o.amount.payableMinor, o.amount.currency) }}
      </text>
      <text class="btn" @tap="delivered(o)">{{ $t("order.delivered") }}</text>
    </view>

    <text class="tip">{{ $t("delivery.noRiderHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.hint {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.save {
  margin-top: 24rpx;
}
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 32rpx 8rpx 16rpx;
}
.row {
  gap: 20rpx;
}
.row__main {
  flex: 1;
  min-width: 0;
}
.row__buyer {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-ink);
}
.row__addr {
  display: block;
  margin-top: 4rpx;
  font-size: 28rpx;
  color: var(--sh-ink);
  line-height: 1.4;
}
.row__addr--none {
  color: var(--sh-sub);
}
.row__sub {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 6rpx;
}
.row__tel {
  font-size: 28rpx;
  font-weight: 600;
  color: var(--sh-primary-text);
}
.row__amount {
  font-size: 30rpx;
  font-weight: 700;
  color: var(--sh-ink);
}
.btn {
  padding: 18rpx 28rpx;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 24rpx;
  font-weight: 600;
}
.tip {
  display: block;
  margin: 32rpx 8rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.6;
}
</style>
