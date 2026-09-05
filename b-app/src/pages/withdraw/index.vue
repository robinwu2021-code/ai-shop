<template>
  <sh-scaffold title-key="withdraw.title" :denied="!merchant.can('biz:finance')">
    <!-- 可提余额：这一页最重要的数字，单独一块。档位与 income / points 的结存一致（txt-mega） -->
    <view class="sh-card">
      <text class="sh-muted">{{ t("withdraw.canWithdraw") }}</text>
      <text class="txt-mega sh-num amt">{{ money(page?.withdrawableMinor ?? 0) }}</text>
      <!--
        下限写出来，别让他点了才知道太少。
        后端也拦，但那时他已经填过一遍金额了。
      -->
      <text class="sh-hint txt-quiet">
        {{ t("withdraw.minHint", { min: money(page?.minAmountMinor ?? 0) }) }}
      </text>
    </view>

    <!-- 申请 -->
    <view class="sh-card">
      <input
        v-model="amountText"
        type="digit"
        maxlength="12"
        class="field__input sh-num"
        :placeholder="t('withdraw.amountPlaceholder')"
      />
      <!--
        用 view 而不是 button：这套界面的按钮形态是 `.sh-btn`，而原生 button 在小程序上
        自带一圈 ::after 描边与最小高度，同一屏里两种按钮会差一圈。禁用态走全局 .is-disabled，
        并在 handler 里也拦一次（view 没有 disabled 属性）。
      -->
      <view
        class="sh-btn sh-mt-sm"
        :class="{ 'is-disabled': !canSubmit || submitting }"
        @tap="submit"
      >
        {{ submitting ? t("withdraw.submitting") : t("withdraw.submit") }}
      </view>
      <!--
        **按钮禁用时要说明原因。**只是灰掉的话，商家不知道是钱不够、
        低于下限、还是有一笔在审 —— 三种情况他该做的事完全不同。
      -->
      <text v-if="blockReason" class="sh-hint is-warning">{{ blockReason }}</text>
    </view>

    <!-- 记录 -->
    <view class="sh-card">
      <text class="txt-strong">{{ t("withdraw.records") }}</text>
      <sh-empty v-if="!page?.records?.length" bare :text="String(t('withdraw.noRecords'))"></sh-empty>
      <!-- 行距与分隔线归 .sh-row--divided：它把线画在「相邻的后一行」上，
           所以不用再判「是不是最后一条」 -->
      <view
        v-for="r in page?.records ?? []"
        :key="r.withdrawNo"
        class="sh-row sh-row--between sh-row--divided"
      >
        <view>
          <text class="txt-strong sh-num rec__amt">{{ money(r.amount) }}</text>
          <text class="txt-caption txt-quiet rec__at">{{ r.appliedAt }}</text>
        </view>
        <view class="rec__r">
          <text class="txt-caption" :class="statusClass(r.status)">
            {{ t(`withdraw.status.${r.status}`) }}
          </text>
          <!-- 驳回理由要显示：不显示的话商家只知道被拒，不知道为什么 -->
          <text v-if="r.remark" class="sh-hint txt-quiet">{{ r.remark }}</text>
        </view>
      </view>
    </view>
  </sh-scaffold>
</template>

<script setup lang="ts">
// 商家提现（V288）。
//
// **这个页面此前不存在，而后端的提现单也从没被创建过** ——
// 两端各缺一半，于是运营端的审批页永远是空的。
// 后端 2026-09-02 补了申请入口，这一页是它的另一半：
// 没有它，那条能力没有任何商家能触达。
//
// ⚠️ 说明写成 script 里的 `//` 而不是文件顶部的 HTML 注释：
// 顶部 HTML 注释会让 UnoCSS 的 transformer 报
// 「Cannot split a chunk that has already been edited」，
// 整个模块 500 加载不出来 —— 而 **vue-tsc 是通过的**。
// 类型检查证明不了能构建，这一条撞过。
//
// ⚠️ 同理：**这里不能用带冒号前缀的变体类**（禁用态、末项去边框那一类）。
// @unocss-applet/preset-applet 处理它们时崩在同一个地方，
// 仓库里其他页面一个都没用过 —— 那不是巧合。用 :class 绑定表达同样的意思。
//
// 而且**连注释里都不能写出那些类名**：transformer 不区分代码与注释，
// 照样去处理它扫到的每一个类名字面量。写进注释同样让模块 500 ——
// 这与「守卫也扫注释」是同一个坑，只是这次踩的是构建工具。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { money } from "@shared/utils/money";
import type { WithdrawPage } from "@/api/contract";

const { t } = useI18n();
/*
 * 页面门禁。**不加的话店员/理货员/配送员进得了这一页，而页面里每个请求都是 403** ——
 * 他看到的是一片空白加几个「网络异常」，读不出「这一页不归我管」。
 * 判据由 packages/shared 的 biz-page-perm 闸门盯着：它比对
 * 「这一页调了哪些接口」与「这一页的门禁覆盖了哪些码」。
 */
const merchant = useMerchantStore();
const page = ref<WithdrawPage | null>(null);
const amountText = ref("");
const submitting = ref(false);

/** 元 → 分。**只在这一处转换**，别让分散的乘 100 在各处漂移 */
const amountMinor = computed(() => {
  const n = Number(amountText.value);
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) : 0;
});

const canSubmit = computed(() =>
  amountMinor.value >= (page.value?.minAmountMinor ?? 0)
  && amountMinor.value <= (page.value?.withdrawableMinor ?? 0)
  && !hasPending.value);

const hasPending = computed(() =>
  (page.value?.records ?? []).some((r) => r.status === "PENDING" || r.status === "APPROVED"));

/** 按钮为什么点不了 —— 三种原因，商家该做的事完全不同 */
const blockReason = computed(() => {
  if (hasPending.value) return t("withdraw.blockPending");
  if (!amountMinor.value) return "";
  if (amountMinor.value < (page.value?.minAmountMinor ?? 0)) return t("withdraw.blockTooSmall");
  if (amountMinor.value > (page.value?.withdrawableMinor ?? 0)) return t("withdraw.blockNotEnough");
  return "";
});

/** 状态色走全局语义类（.is-success / .is-danger / .is-warning）——
 *  字号由调用点的 .txt-caption 给，这里只回颜色那一档 */
function statusClass(s: string) {
  if (s === "PAID") return "is-success";
  if (s === "REJECTED" || s === "FAILED") return "is-danger";
  return "is-warning";
}

async function load() {
  page.value = await api.mWithdrawPage();
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  try {
    await api.mApplyWithdraw(amountMinor.value);
    amountText.value = "";
    uni.showToast({ title: t("withdraw.submitted"), icon: "none" });
    await load();
  } finally {
    // finally 里收：失败时按钮不解锁的话，页面看起来卡死了
    submitting.value = false;
  }
}

onShow(load);
</script>

<style scoped>
/* 大数与它上面那行标签之间的缝。字号与字重归 .txt-mega */
.amt {
  display: block;
  margin-top: 8rpx;
}
.rec__amt {
  display: block;
}
.rec__at {
  display: block;
  margin-top: 4rpx;
}
/* 右列贴右。用逻辑属性 —— 阿语下要跟着翻 */
.rec__r {
  text-align: end;
}
</style>
