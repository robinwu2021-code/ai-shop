<script setup lang="ts">
import { useMerchantStore } from "@/stores/merchant";

const merchant = useMerchantStore();
// 分拣单（B-10.3）。到货当日的高频页面。
//
// 两个视图不是花哨：**按商品**是分货时用的（一箱苹果拆成 12 份，按商品走一遍最快），
// **按用户**是装袋时用的（每人一袋，按人走一遍不会漏）。同一批货要走两遍不同的顺序，
// 只做一个视图就等于让店主自己在纸上抄一遍。
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { ROUTES } from "@/shared/nav";
import type { Order, PickingRow, PickupOrder } from "@shared/types";
import { confirm, pick, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();

const view = ref<"goods" | "buyer">("goods");
const rows = ref<PickingRow[]>([]);
const orders = ref<PickupOrder[]>([]);

/** 按用户聚合：从分拣行反查，避免再拉一次订单接口 */
const byBuyer = computed(() => {
  const map = new Map<string, { nickname: string; items: { title: string; spec: string; qty: number }[] }>();
  for (const r of rows.value) {
    for (const b of r.buyers) {
      const cur = map.get(b.orderNo) ?? { nickname: b.nickname, items: [] };
      cur.items.push({ title: r.title, spec: r.spec, qty: b.qty });
      map.set(b.orderNo, cur);
    }
  }
  return [...map.entries()].map(([orderNo, v]) => ({ orderNo, ...v }));
});

const totalQty = computed(() => rows.value.reduce((s, r) => s + r.totalQty, 0));
/** 备货中的单 = 还没标到货的，标记后用户才收到到货通知 */
/**
 * 备货中 = 还没登记到货的自提单。
 *
 * **判据是子单状态 `WAIT_FULFILL`**，不是主单的 `PAID` ——
 * `/biz/pickup/orders` 发的是子单那一套（与核销台同一个数据源）。
 * 按 `PAID` 过滤的话真机上这一区永远是空的，而 mock 里一切正常。
 */
const preparing = computed(() => orders.value.filter((o) => o.status === "WAIT_FULFILL"));

/** 我能不能看自提单 —— `/biz/pickup/orders` 要 `biz:verify`，理货员没有 */
const canPickupOrders = computed(() => merchant.can("biz:verify"));

/*
 * 两件事各自取，**不用裸 Promise.all**。
 *
 * 这一页的门禁是 `biz:receive`，而自提单接口要的是 `biz:verify` ——
 * **理货员（PICKER = receive + stock）恰好只有前者**，而这一页就是为他设的。
 * 原先绑在一个 Promise.all 里，自提单被 70006 拒就整体 reject，
 * 连分拣单也一起没了：他打开自己的工作页，看到的是一片空白。
 *
 * 到货登记（下面的「备货中」区）依赖自提单，所以它跟着 `biz:verify` 走 ——
 * 标到货会触发给买家的到货通知，属于交付面，与核销同一档（RECEIVE 对货、VERIFY 对顾客）。
 */
async function load() {
  // 先等权限到位 —— `can()` fail-closed，深链进来时 onShow 早于外壳的 ensureScope
  await merchant.ensureScope();
  const [r, o] = await Promise.all([
    api.mPickingList().catch(() => []),
    canPickupOrders.value ? api.mPickupOrders().catch(() => []) : Promise.resolve([]),
  ]);
  rows.value = r;
  orders.value = o;
}

/**
 * 破损 / 短少上报（B-10.4.2）。
 * **只留痕并通知用户，不自动退款** —— 责任在供货方还是自提点承接方尚未定（矩阵 M4），
 * 自动退等于默认平台兜底。
 *
 * **数量必须真的问一句**：此前这里没有数量输入，后端不管缺 1 件还是 5 件都落成 1 ——
 * 分拣汇总的短缺数字从设计上就是错的。`expectedQty` 预填这个人这个规格订的量，
 * 大多数情况是"这一份全没了"，直接确认就行，不用每次都手输。
 */
async function report(orderNo: string, skuNo: string, expectedQty: number) {
  const kinds = [t("picking.shortage"), t("picking.damage")] as const;
  const res = await pick({ items: [...kinds] });
  if (res === null) return;

  const qtyInput = (await prompt({
    title: String(t("picking.qtyTitle", { s: kinds[res]! })),
    value: String(expectedQty),
    placeholder: String(expectedQty),
    type: "number",
  })) ?? "";
  if (!qtyInput.trim()) return;
  const qty = Number(qtyInput.trim());
  if (!Number.isFinite(qty) || qty < 1 || !Number.isInteger(qty)) {
    uni.showToast({ title: t("picking.qtyInvalid"), icon: "none" });
    return;
  }

  const note = (await prompt({
    title: String(kinds[res]!),
    placeholder: String(t("picking.notePh")),
  })) ?? "";
  if (!note.trim()) return;

  await api.mReportShortage(orderNo, {
    skuNo,
    kind: res === 0 ? "SHORTAGE" : "DAMAGE",
    qty,
    note: note.trim(),
  });
  uni.showToast({ title: t("picking.reported"), icon: "none" });
}

/**
 * 标记到货之后引导去核销台——这两步是同一条流水线上前后相邻的两道工序
 * （分拣理货 → 标到货 → 核销交付），此前标完到货只弹个 toast，商家要自己
 * 退回首页再找核销入口，多走两三步。**只在他有核销权限时才弹**：
 * 理货员（只有 receive）打不开核销台，弹一个他点不动的按钮比不弹更糟。
 */
async function markAllArrived() {
  if (!preparing.value.length) return;
  // 到货登记按**子单号**：后端 markArrived 收的就是子单号（一张主单可能拆给几家）
  const changed = await api.mMarkArrived(preparing.value.map((o) => o.subOrderNo));
  await load();
  if (!merchant.can("biz:verify")) {
    uni.showToast({ title: t("picking.arrivedDoneTitle", { n: changed.length }), icon: "none" });
    return;
  }
  if (
    await confirm({
      title: String(t("picking.arrivedDoneTitle", { n: changed.length })),
      hint: String(t("picking.arrivedDoneBody")),
      confirmText: String(t("picking.goVerify")),
    })
  ) {
    uni.navigateTo({ url: ROUTES.verify });
  }
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="picking.title" :denied="!merchant.can('biz:receive')">
    <view class="head sh-row sh-row--between sh-row--baseline">
      <text class="txt-display">{{ $t("picking.title") }}</text>
      <text class="sh-muted">{{ $t("picking.total", { n: totalQty }) }}</text>
    </view>

    <text class="sh-muted reporthint">{{ $t("picking.reportHint") }}</text>

    <sh-tabs
      :items="[
        { key: 'goods', label: String($t('picking.byGoods')) },
        { key: 'buyer', label: String($t('picking.byBuyer')) },
      ]"
      :active="view"
      @change="(k: string) => (view = k as 'goods' | 'buyer')"
    ></sh-tabs>

    <sh-empty v-if="!rows.length" :text='$t("picking.empty")'></sh-empty>

    <!-- 按商品：分货用 -->
    <template v-if="view === 'goods'">
      <view v-for="r in rows" :key="r.skuNo" class="sh-card sh-mb-sm">
        <view class="row__head sh-row">
          <sh-cover class="row__cover" :src="r.cover"></sh-cover>
          <view class="sh-fill">
            <text class="txt-strong row__title">{{ r.title }}</text>
            <text class="sh-muted">{{ r.spec }}</text>
          </view>
          <text class="txt-title row__qty sh-num">×{{ r.totalQty }}</text>
        </view>
        <view class="buyers sh-wrap">
          <text
            v-for="b in r.buyers"
            :key="b.orderNo"
            class="sh-chip"
            @tap="report(b.orderNo, r.skuNo, b.qty)"
          >
            {{ b.nickname }} ×{{ b.qty }}
          </text>
        </view>
      </view>
    </template>

    <!-- 按用户：装袋用 -->
    <template v-else>
      <view v-for="b in byBuyer" :key="b.orderNo" class="sh-card sh-mb-sm">
        <view class="row__head sh-row">
          <view class="sh-fill">
            <text class="txt-strong row__title">{{ b.nickname }}</text>
            <text class="sh-muted sh-num">{{ b.orderNo }}</text>
          </view>
          <text class="txt-title row__qty sh-num">
            ×{{ b.items.reduce((s, i) => s + i.qty, 0) }}
          </text>
        </view>
        <view class="buyers sh-wrap">
          <text v-for="(it, i) in b.items" :key="i" class="sh-chip">
            {{ it.title }} ×{{ it.qty }}
          </text>
        </view>
      </view>
    </template>

    <view
      v-if="preparing.length"
      class="sh-btn arrive"
      @tap="markAllArrived"
    >
      {{ $t("picking.markArrived", { n: preparing.length }) }}
    </view>
  </sh-scaffold>
</template>

<style scoped>
.head {
  margin-bottom: 20rpx;
}
.reporthint {
  display: block;
  margin: 0 8rpx 20rpx;
}
.tabs .sh-chip {
  padding: 14rpx 28rpx;
}
/* 列表密度对齐 C 端（平台版式约定）：卡片之间只留一条缝、正文行高 1.35。
   商家一天要扫几十次这类列表，行距每多 10rpx，一屏就少一行。 */

.row__head {
  gap: 20rpx;
}
.row__cover {
  font-size: 52rpx;
  width: 84rpx;
  height: 84rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
  text-align: center;
  line-height: 84rpx;
}

.row__title {
  display: block;
}
.row__qty {
  color: var(--sh-primary-text);
}
.buyers {
  margin-top: 20rpx;
}
.arrive {
  margin-top: 24rpx;
}
</style>
