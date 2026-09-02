<script setup lang="ts">
/**
 * 挑货 —— 开单时选货那一步，进货 / 报损 / 调拨三处共用。
 *
 * **抽出来是因为判据不一致，不是因为代码重复。** 抽之前三处各写一个裸列表：
 * 没有搜索、没有已选计数，几百个 SKU 时得滚十几屏去找一件 —— 而盘点页本轮
 * 刚加了搜索，于是同一个动作在四个地方是四种操作。重复的行数很少（七八行），
 * 真正的问题是**同一件事长得不一样**。
 *
 * **搜索在端上做，不发请求。** 挑货的候选一次取 200 条已经在手里，
 * 每敲一个字发一趟的话，弱网下商家看到的是列表跳来跳去。
 *
 * 盘点页不用这个件：它是整页勾选（先选一批再开单），形态不同 ——
 * 硬塞进弹层反而把「一次盘一批」压回「一次挑一件」。
 */
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { scanCode } from "@shared/ports/scan";
import type { StockBalance } from "@shared/types";

const props = withDefaults(
  defineProps<{
    visible: boolean;
    title: string;
    items: StockBalance[];
    /** 已选的 itemId。已选的置灰并标出来 —— 不标的话商家会重复点同一件 */
    picked?: string[];
    /** 右侧那个数的说明，如「账面 {n}」「可用 {n}」。各屏关心的数不一样 */
    qtyLabel?: (b: StockBalance) => string;
  }>(),
  { picked: () => [], qtyLabel: undefined },
);

const emit = defineEmits<{ pick: [b: StockBalance]; close: [] }>();

const { t } = useI18n();
const keyword = ref("");

// 关掉时清空关键词：留着的话下次打开是上一次的筛选结果，
// 而商家以为看到的是全部 —— 一个静默的空列表
watch(() => props.visible, (v) => {
  if (!v) {
    keyword.value = "";
    // 待绑的码也要清：留着的话下次打开弹层，随手挑一件就会把它绑上去
    pendingCode.value = "";
  }
});

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase();
  if (!k) return props.items;
  return props.items.filter(
    (b) => b.name.toLowerCase().includes(k) || (b.specText ?? "").toLowerCase().includes(k),
  );
});

function isPicked(b: StockBalance): boolean {
  return props.picked.includes(b.itemId);
}

/**
 * 扫码找货。**加在这个件上就同时覆盖进货 / 报损 / 调拨三处** ——
 * 各页各写一遍的话，三处的失败提示迟早各自漂。
 *
 * 三段：
 * ① 命中 → 直接当成挑了这一件，不用再翻列表；
 * ② 没绑过 → **不报错**，把码留着，让商家从列表里选一件绑上（`pendingCode`）；
 * ③ 扫码取消 / 不可用 → 静默返回，商家继续用搜索。
 *
 * **第一天必然全是②**：线上 `prd_sku.barcode` 是 0/396。这不是缺陷，是设计 ——
 * 已拍板不做批量补录，数据靠用出来。所以②那条路要好走，不能是一句错误提示。
 */
const scanning = ref(false);
/** 扫到了但没绑过的那个码。有值时列表处于「选一件货绑给它」的状态 */
const pendingCode = ref("");

async function scan() {
  if (scanning.value) return;
  scanning.value = true;
  try {
    const code = (await scanCode()).trim();
    if (!code) return;
    const hit = await api.mItemByBarcode(code);
    if (hit) {
      pendingCode.value = "";
      emit("pick", hit);
      return;
    }
    // 没绑过：留着这个码，等他从列表里选一件
    pendingCode.value = code;
    uni.showToast({ title: String(t("stockPick.scanUnknown")), icon: "none" });
  } catch {
    // 取消扫码、或这个端不支持 —— 都不是错误，静默回到搜索
  } finally {
    scanning.value = false;
  }
}

/** 选中一行。**有待绑的码时先绑再挑** —— 绑完下次扫同一件直接命中 */
async function choose(b: StockBalance) {
  const code = pendingCode.value;
  if (!code) {
    emit("pick", b);
    return;
  }
  pendingCode.value = "";
  /*
   * **没有 skuNo 就别绑，也别拿 itemId 冒充。**
   *
   * 条码的真源是商品域的 `prd_sku.barcode`，那边不认识进销存的 itemId。
   * 2026-09-02 这里原本写着 `props.skuNoOf ? props.skuNoOf(b) : b.itemId`，
   * 而三个调用页一个都没传那个 prop（BalanceVO 当时也没有 skuNo，传不出来）——
   * 于是每次绑码都拿 itemId 去当 skuNo，后端 NOT_FOUND，catch 吞成一个
   * 一闪而过的 toast，货照样选中。**绑码 100% 失败，而界面上看不出来**：
   * 真机上连扫两次同一个码，第二次仍然提示「还没绑过」。
   *
   * 两个域的 ID 长得都像编号，冒充了不会有人报错 —— 所以这里宁可不绑。
   */
  if (!b.skuNo) {
    // 没有映射的物料（独立交付形态下的自有主数据）绑不了码。**说出来**，
    // 静默跳过的话，商家会以为绑上了，下次扫不中时无从判断是哪一步没成
    uni.showToast({ title: String(t("stockPick.scanNoSku")), icon: "none" });
    emit("pick", b);
    return;
  }
  try {
    await api.mBindBarcode({ skuNo: b.skuNo, barcode: code });
    uni.showToast({ title: String(t("stockPick.scanBound")), icon: "none" });
  } catch (e) {
    // **绑失败不挡挑货**：他这一单还是要记的，码下次再绑
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
  emit("pick", b);
}
</script>

<template>
  <sh-sheet :visible="visible" :title="title" @close="emit('close')">
    <!--
      搜索常驻，不做成「货多了才出现」：出现与否取决于数据，人就学不会它在哪。

      类是 `field__input`（base.css 里定义的那一个）。**没有 `sh-input` 这个类** ——
      写它不会报错，只是没有边框：那一行看上去是一句灰色提示语，
      商家根本不知道能点进去打字。
    -->
    <input
      v-model="keyword"
      class="field__input pick__search"
      :placeholder="String($t('stockPick.searchPh'))"
      :maxlength="32"
      confirm-type="search"
    />

    <!--
      扫码按钮贴着搜索框：它们是同一件事的两种做法（找到那件货），
      分开摆的话商家会以为扫码是另一个功能。
    -->
    <text class="sh-link pick__scan" @tap="scan">{{ $t("stockPick.scan") }}</text>

    <!-- 扫到了没绑过：这一屏此刻的意思变了，得说出来，否则他不知道点一行会发生什么 -->
    <text v-if="pendingCode" class="sh-hint pick__pending">
      {{ $t("stockPick.scanBindHint", { code: pendingCode }) }}
    </text>

    <text v-if="picked.length" class="sh-hint pick__count">
      {{ $t("stockPick.picked", { n: picked.length }) }}
    </text>

    <sh-empty v-if="!shown.length" compact :text="String($t('stockPick.empty'))"></sh-empty>

    <view
      v-for="b in shown"
      :key="b.itemId"
      class="pick sh-row sh-row--between sh-row--baseline"
      :class="{ 'pick--on': isPicked(b) }"
      @tap="choose(b)"
    >
      <!--
        「已下架」跟在名字后面，不另起一行 —— 它是**这一行是哪件货**的一部分，
        不是附加信息。线上有 13 组同名同规格的物料，弹层里几行完全一样
        （同库位、库存也一样），不标出来商家挑哪一行都不知道自己挑的是什么。
      -->
      <text class="txt-body">
        {{ b.name }}{{ b.specText ? ` · ${b.specText}` : "" }}
        <text v-if="b.flags.includes('OFF_SALE')" class="sh-muted">{{ $t("stock.offSale") }}</text>
      </text>
      <text class="sh-muted sh-num">
        {{ qtyLabel ? qtyLabel(b) : b.onHand }}
      </text>
    </view>
  </sh-sheet>
</template>

<style scoped>
.pick__search {
  margin-bottom: 12rpx;
}
.pick__count {
  display: block;
  padding-bottom: 8rpx;
}
/* 扫码贴着搜索框右下：它是搜索的另一种做法，不是另一个功能 */
.pick__scan {
  display: block;
  text-align: end;
  padding-bottom: 8rpx;
}
.pick__pending {
  display: block;
  padding-bottom: 8rpx;
}
.pick {
  padding: 20rpx 0;
}
.pick + .pick {
  border-top: var(--sh-hairline-soft);
}
/* 已选的压暗但**仍可点** —— 再点一次是加一行（同一件货分两笔进货是常事），
   禁掉的话商家以为坏了 */
.pick--on {
  opacity: 0.45;
}
</style>
