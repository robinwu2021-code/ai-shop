<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 门店管理（M6）。
//
// 与「店铺设置」的分工：那一页管**一家店的门面**（公告/营业时间/地址/主推），
// 这一页管**有几家店、哪家是哪家**。分开是因为前者天天改、后者一年动不了几次，
// 且后者每个动作都有硬约束（额度、默认店唯一、收款号必须是自己的）。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api, ApiError } from "@/api";
import { ROUTES } from "@/shared/nav";
import type { MerchantPlan, PaymentApplyment, Store } from "@shared/types";

/**
 * 门店额度用尽（后端 `ErrorCode.STORE_QUOTA_EXCEEDED`）。
 *
 * **它与别的建店失败不是一类**：名字重了改个名就好，这个改什么都一样被拒 ——
 * 他要做的是升档或停用一家旧店。按普通 toast 处理的话，
 * 店主会以为是表单填错了，反复改门店名。
 */
const QUOTA_EXCEEDED = 70020;

const { t } = useI18n();

const stores = ref<Store[]>([]);
const plan = ref<MerchantPlan | null>(null);
const payments = ref<PaymentApplyment[]>([]);
const busy = ref(false);

/** 新建表单：默认收起 —— 大多数商家只有一家店，天天看到一个空表单是噪音 */
const adding = ref(false);
const form = ref({ name: "", address: "" });

/** 可挑的收款号：只列**已开通**的。没开通的挂上去，下一单就收不了款 */
const payOptions = computed(() =>
  payments.value.filter((p) => p.canReceiveMoney && p.payMerchantNo),
);

onShow(load);

async function load() {
  stores.value = await api.mStoreList().catch(() => []);
  payments.value = await api.mPayments().catch(() => []);
  // 静默失败：拿不到套餐只是少一句额度提示，不该让这一页报错
  plan.value = await api.mMyPlan().catch(() => null);
}

async function run(fn: () => Promise<unknown>) {
  if (busy.value) return;
  busy.value = true;
  try {
    await fn();
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    busy.value = false;
  }
}

function create() {
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("stores.needName"), icon: "none" });
    return;
  }
  if (busy.value) return;
  busy.value = true;
  void (async () => {
    try {
      await api.mCreateStore({ name: form.value.name.trim(), address: form.value.address.trim() });
      form.value = { name: "", address: "" };
      adding.value = false;
    } catch (e) {
      // ★ 额度被挡**不走那个通用 toast** —— 见 onQuotaBlocked
      if (e instanceof ApiError && e.code === QUOTA_EXCEEDED) {
        await onQuotaBlocked();
      } else {
        uni.showToast({ title: (e as Error).message, icon: "none" });
      }
    } finally {
      busy.value = false;
      await load();
    }
  })();
}

/**
 * 额度被挡时的出路（步骤 4.2）。
 *
 * <p>**当场给出下一步**，而不是只说一句「额度不足」：他正要开第二家店，
 * 这是购买意图最明确的一刻 —— 把他打回一个 toast，这次意图就没了。
 *
 * <p>还没试用过的人**直接给「免费试用」**：需求原话是「不要只弹额度不足」。
 * 试用过的、或者已经是付费档的，跳到套餐页（那里有档位对比与联系方式）。
 */
async function onQuotaBlocked() {
  const p = plan.value ?? (await api.mMyPlan().catch(() => null));
  const canTrial = !!p?.trialTier;
  const r = await new Promise<UniApp.ShowModalRes | null>((resolve) => {
    uni.showModal({
      title: String(t("plan.blockedTitle")),
      content: String(t("plan.blockedBody", {
        name: p?.planName ?? "",
        quota: p?.storeQuota ?? stores.value.length,
      })),
      // 主按钮就是那条出路：能试用给试用，否则给「查看套餐」
      confirmText: canTrial
        ? String(t("plan.blockedTrial", { n: p?.trialDays ?? 0 }))
        : String(t("plan.blockedView")),
      cancelText: String(t("common.cancel")),
      success: resolve,
      fail: () => resolve(null),
    });
  });
  if (!r?.confirm) return;
  if (!canTrial) {
    uni.navigateTo({ url: ROUTES.plan });
    return;
  }
  try {
    plan.value = await api.mStartTrial();
    uni.showToast({ title: String(t("plan.trialStarted")), icon: "none" });
    // 额度立即生效，所以**当场把他刚填的那家店建出来** ——
    // 让他再点一遍「保存」是把一次成功拆成两步，而中间那一步会掉人
    if (form.value.name.trim()) {
      await api.mCreateStore({ name: form.value.name.trim(), address: form.value.address.trim() });
      form.value = { name: "", address: "" };
      adding.value = false;
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message || String(t("plan.trialFailed")), icon: "none" });
  }
}

/**
 * 改名。
 *
 * **地址一起带过去**：`mRenameStore` 收的是整个 `StoreEditReq`，
 * 只传 name 的话地址会被后端当成「改成空」——「改个名字顺手把地址清了」
 * 是那种要过很久才有人发现的错。改地址本身另说，这里只保证不弄丢它。
 */
function rename(s: Store) {
  uni.showModal({
    title: t("stores.rename"),
    editable: true,
    placeholderText: t("stores.namePh"),
    content: s.name,
    success: (r) => {
      const name = (r.content ?? "").trim();
      if (!r.confirm || !name || name === s.name) return;
      run(() => api.mRenameStore(s.storeNo, { name, address: s.address }));
    },
  });
}

/** 停用是「不再接新单」，已有的单照常履约 —— 文案要说清，否则没人敢点 */
function toggleStatus(s: Store) {
  run(() => api.mSetStoreStatus(s.storeNo, s.status !== "ACTIVE"));
}

function makeDefault(s: Store) {
  run(() => api.mSetDefaultStore(s.storeNo));
}

function goPlan() {
  uni.navigateTo({ url: ROUTES.plan });
}

/** 传空 = 回到主体默认收款号，是合法操作 */
function pickPayment(s: Store, payMerchantNo?: string) {
  run(() => api.mSetStorePayment(s.storeNo, payMerchantNo));
}
</script>

<template>
  <sh-scaffold title-key="stores.title" :denied="!merchant.can('biz:store:admin')">
    <view class="head">
      <text class="sh-h1">{{ $t("stores.title") }}</text>
      <text class="sh-muted mt">{{ $t("stores.hint") }}</text>
    </view>

    <view v-for="s in stores" :key="s.storeNo" class="sh-card st">
      <view class="st__top">
        <text class="sh-h2">{{ s.name }}</text>
        <view class="tags">
          <text v-if="s.isDefault" class="tag tag--primary">{{ $t("stores.default") }}</text>
          <!--
            ★ 两种只读必须分开显示：`status` 一模一样，而下一步完全不同 ——
            平台压的要补缴/升档，自己停的点一下启用就开。
            不分开的表现是店主反复点那个对降级店无效的「启用」。
          -->
          <text v-if="s.planSuspended" class="tag tag--warn">{{ $t("stores.planSuspended") }}</text>
          <text v-else-if="s.status !== 'ACTIVE'" class="tag">{{ $t("stores.disabled") }}</text>
          <!-- 收不了钱要显眼：店开着但钱进不来，是最容易被忽略的一种坏 -->
          <text v-if="!s.payReady" class="tag tag--warn">{{ $t("stores.payNotReady") }}</text>
        </view>
      </view>

      <text v-if="s.address" class="addr">{{ s.address }}</text>
      <text class="meta">{{ $t("stores.staffCount", { n: s.staffCount }) }}</text>

      <!-- 收款号：空 = 用主体默认号，这是常态不是缺配置 -->
      <view class="pay">
        <text class="pay__label">{{ $t("stores.payment") }}</text>
        <view class="pay__opts">
          <text
            class="sh-chip"
            :class="{ 'sh-chip--primary': !s.payMerchantNo }"
            @tap="pickPayment(s, undefined)"
          >
            {{ $t("stores.payDefault") }}
          </text>
          <text
            v-for="p in payOptions"
            :key="p.payMerchantNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': s.payMerchantNo === p.payMerchantNo }"
            @tap="pickPayment(s, p.payMerchantNo)"
          >
            {{ p.channelName }}
          </text>
        </view>
      </view>

      <view class="acts">
        <!--
          改名。后端与契约一直都在，**这一页却只有建店/停用/设默认/挂收款号四个动作** ——
          于是开错一个字的店名只能停用重建，而重建会丢掉这家店的历史。
        -->
        <text class="act" @tap="rename(s)">{{ $t("stores.rename") }}</text>
        <text v-if="!s.isDefault && s.status === 'ACTIVE'" class="act" @tap="makeDefault(s)">
          {{ $t("stores.setDefault") }}
        </text>
        <!-- 默认店没有停用入口：后端也会拒，但按钮就不该出现在那儿 -->
        <!--
          降级压下的店**不给「启用」按钮**：点了后端也不会放行（额度还是不够），
          而一个点了没反应的按钮比没有按钮更让人困惑。给的是「去看套餐」。
        -->
        <text v-if="s.planSuspended" class="act" @tap="goPlan">{{ $t("stores.planSuspendedAct") }}</text>
        <text v-else-if="!s.isDefault" class="act" @tap="toggleStatus(s)">
          {{ s.status === "ACTIVE" ? $t("stores.disable") : $t("stores.enable") }}
        </text>
      </view>
    </view>

    <view v-if="!adding" class="sh-btn sh-btn--soft add" @tap="adding = true">
      {{ $t("stores.add") }}
    </view>

    <view v-else class="sh-card mt-card">
      <text class="sh-h2">{{ $t("stores.add") }}</text>
      <!--
        额度说明放在表单里而不是报错后才说：让人白填一遍再被拒是没道理的。
        **带上真实数字**（「成长版 · 门店 2/3」）—— 一句泛泛的「有上限」
        既不能让他放心也不能让他行动。
      -->
      <text class="hint">
        {{ plan
          ? $t("plan.meSub", { name: plan.planName, used: plan.storeUsed, quota: plan.storeQuota })
          : $t("stores.quotaHint") }}
      </text>

      <view class="field">
        <text class="field__label">{{ $t("stores.name") }}</text>
        <input v-model="form.name" class="field__input" :placeholder="$t('stores.namePh')" />
      </view>
      <view class="field">
        <text class="field__label">{{ $t("stores.address") }}</text>
        <input v-model="form.address" class="field__input" :placeholder="$t('stores.addressPh')" />
      </view>

      <view class="sh-btn submit" @tap="create">{{ $t("common.save") }}</view>
      <view class="sh-btn sh-btn--soft cancel" @tap="adding = false">{{ $t("common.cancel") }}</view>
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head {
  padding: 32rpx 32rpx 8rpx;
}
.mt {
  margin-top: 12rpx;
}
.mt-card {
  margin-top: 24rpx;
}
.st {
  margin-top: 24rpx;
}
.st__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.tags {
  display: flex;
  gap: 10rpx;
}
.tag {
  padding: 4rpx 14rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  font-size: 24rpx;
  color: var(--sh-sub);
}
.tag--primary {
  background: var(--sh-primary-tint);
  color: var(--sh-primary);
}
.tag--warn {
  color: var(--sh-danger);
}
.addr,
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.pay {
  margin-top: 20rpx;
}
.pay__label {
  display: block;
  font-size: 24rpx;
  color: var(--sh-sub);
}
.pay__opts {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
  margin-top: 12rpx;
}
.acts {
  display: flex;
  gap: 24rpx;
  margin-top: 20rpx;
}
.act {
  font-size: 26rpx;
  color: var(--sh-primary);
}
.field {
  margin-top: 28rpx;
}
.field__label {
  display: block;
  font-size: 26rpx;
  color: var(--sh-sub);
}
.field__input {
  margin-top: 12rpx;
  padding: 20rpx 24rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  font-size: 28rpx;
  color: var(--sh-ink);
}
.hint {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  line-height: 1.5;
  color: var(--sh-sub);
}
.add {
  margin-top: 24rpx;
}
.submit {
  margin-top: 32rpx;
}
.cancel {
  margin-top: 16rpx;
}
</style>
