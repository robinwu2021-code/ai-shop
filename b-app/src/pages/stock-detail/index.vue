<script setup lang="ts">
// 库存明细（B-2）—— **本期真正的交付物**。
//
// 改库存是店员和理货员每天的活（`biz:stock` 他们都有），而在此之前平台连
// 「谁改的」都答不上来。这一页把那个问题变成可回答的：每一行都带操作人、
// 单据号、变动前后 —— 「昨天还有 20 袋今天剩 3 袋」从这一屏开始能回答。
//
// **「改数」不是设置库存**：它按盘点走（后端 `/biz/inventory/adjust` 落一张单、
// 落一行流水）。差异不为 0 时原因必填 —— 自由文本汇总不出「这个月报损了多少」，
// 而那正是月底商家唯一想知道的数。
import { ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import type { StockItemDetail, StockLedgerRow } from "@shared/types";
import { uomLabel } from "@shared/utils/format";
import { pick, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const itemId = ref("");
const detail = ref<StockItemDetail | null>(null);
const ledger = ref<StockLedgerRow[]>([]);
const cursor = ref<number | null>(null);
const loading = ref(false);

/**
 * 差异原因。**枚举不是自由文本** —— 与后端 `reason_code` 同一套。
 *
 * 它此前是页面上一张常驻的卡，还预选着「损坏」。而这一页九成的来访是**看**，
 * 不是盘：那张卡每次都占一整屏格子，且只有点了「盘这一件」才用得上。
 * 更糟的是预选 —— 真去盘的时候，人多半不会注意到底下已经替他选了一个。
 * 现在挪进流程里，且**只在差异不为 0 时问**（这一页的注释本来就是这么写的，
 * 只是界面没照做）。
 */
const REASONS = ["BROKEN", "EXPIRED", "GIFT", "OTHER"] as const;

onLoad((q) => {
  itemId.value = String((q as Record<string, string>)?.itemId ?? "");
});

async function load() {
  if (!itemId.value) return;
  loading.value = true;
  try {
    const [d, page] = await Promise.all([
      api.mStockItem(itemId.value),
      api.mStockLedger({ itemId: itemId.value, size: 20 }),
    ]);
    detail.value = d;
    ledger.value = page.entries;
    cursor.value = page.nextCursor ?? null;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

/** 再读一页。**游标由服务端给** —— 自己拿最后一行的 id 推，同一毫秒有多笔时会漏行 */
async function more() {
  if (cursor.value == null) return;
  const page = await api.mStockLedger({ itemId: itemId.value, cursor: cursor.value, size: 20 });
  ledger.value = [...ledger.value, ...page.entries];
  cursor.value = page.nextCursor ?? null;
}

/**
 * 改数。先问点出来是多少，再问差在哪 —— **原因只在有差异时问**：
 * 没差异还要选个原因，人会随手点第一个，那一栏就此变成噪音。
 */
async function adjust() {
  if (!detail.value) return;
  const input = await prompt({
    title: String(t("stockDetail.adjustTitle")),
    hint: String(t("stockDetail.adjustHintDialog", { n: detail.value.onHand })),
    // 整数键盘：库存不带小数。给全键盘的话，称重类商家会输 1.5 然后被拒
    type: "number",
    value: String(detail.value.onHand),
  });
  if (input == null || input === "") return;
  const counted = Number(input);
  if (!Number.isInteger(counted) || counted < 0) {
    uni.showToast({ title: String(t("stockDetail.adjustBadNumber")), icon: "none" });
    return;
  }
  const diff = counted - detail.value.onHand;

  // 有差异才问原因；取消就整件事作罢 —— 不要「原因没选就按默认记一笔」，
  // 那会让月底的报损汇总里混进一堆没人选过的「损坏」
  let reasonCode: string | undefined;
  if (diff !== 0) {
    const idx = await pick({
      title: String(t("stockDetail.reasonLabel")),
      hint: String(t("stockDetail.reasonHint", { n: diff > 0 ? `+${diff}` : String(diff) })),
      items: REASONS.map((r) => String(t(`stock.reason.${r}`))),
    });
    if (idx === null) return;
    reasonCode = REASONS[idx];
  }

  try {
    await api.mStockAdjust({ itemId: itemId.value, countedQty: counted, reasonCode });
    uni.showToast({ title: String(t("common.saved")), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/**
 * 设安全库存。**这一页此前没有它** —— 两张表的列、缺货判据、列表标红全都在，
 * 就是没有任何地方能改，于是默认全 0 让「缺货」只在可用见底时才亮：
 * 预警功能整个是哑的，而库存页第一栏和工作台那张卡的第二个数都指着它。
 *
 * `locationId` 不传 = 改物料默认值（一个库位的商家只会用到这一级）；
 * 传了 = 只改那个库位，**留空提交 = 撤掉覆盖**跟回默认值。
 */
async function editSafety(loc?: { locationId: string; locationName: string; safetyStock?: number }) {
  if (!detail.value) return;
  const current = loc ? loc.safetyStock : detail.value.safetyStock;
  const input = await prompt({
    title: String(t("stockDetail.safetyTitle")),
    hint: loc
      ? String(t("stockDetail.safetyLocHint", { name: loc.locationName, n: detail.value.safetyStock }))
      : String(t("stockDetail.safetyHint")),
    type: "number",
    // 库位那一级允许提交空串 —— 那就是「撤掉覆盖」。物料那一级空串当取消
    value: current == null ? "" : String(current),
  });
  if (input == null) return;

  let qty: number | null;
  if (input === "") {
    // 物料默认值没有「撤掉」这个含义（那一列 NOT NULL），空串按取消处理
    if (!loc) return;
    qty = null;
  } else {
    qty = Number(input);
    if (!Number.isInteger(qty) || qty < 0) {
      uni.showToast({ title: String(t("stockDetail.safetyBadNumber")), icon: "none" });
      return;
    }
  }

  try {
    await api.mSafetyStock(itemId.value, { locationId: loc?.locationId, qty });
    uni.showToast({ title: String(t("common.saved")), icon: "none" });
    // **改完要读回来**：不重新拉一次的话，界面上看不出这次提交有没有生效，
    // 而「写了没读」正是这一条要补的那类洞
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** `0` 显示成「不预警」：它是有意的取值，不是没设好的数 */
function safetyText(n?: number): string {
  if (n == null) return String(t("stockDetail.safetyFollow"));
  return n > 0 ? String(n) : String(t("stockDetail.safetyOff"));
}

/**
 * 「08-26 14:22」。**不走 `new Date()` 解析** —— 后端发的是 `LocalDateTime`
 * （`2026-08-26T14:22:00`，不带时区），浏览器会按本地时区解读它，
 * 于是同一笔流水在不同设备上差几个小时，而且不报错。直接重排字符串没有这个洞。
 */
function at(iso: string): string {
  // 切片而不是正则：UnoCSS 的 variant-group transformer 会把正则里的
  // `(\d{2}-\d{2})` 当成 class 变体组去改写，构建直接报
  // 「Cannot split a chunk that has already been edited」——
  // 报错指向这一行，但原因与这一行的逻辑毫无关系。
  return iso.length >= 16 ? `${iso.slice(5, 16).replace("T", " ")}` : iso;
}

/**
 * 回边：从一行流水走到它那张单。**此前这里是死胡同** ——
 * 看到单号只能记住它，再回单据列表里自己翻。
 */
function openDoc(r: StockLedgerRow) {
  uni.navigateTo({ url: `/pages/stock-docs/index?no=${encodeURIComponent(r.docNo)}` });
}

/** 变动前的数：`balanceAfter − qtyDelta`。界面上要写成「前 → 后」，只给一个数看不出发生了什么 */
function before(r: StockLedgerRow): number {
  return r.balanceAfter - r.qtyDelta;
}

onShow(load);
</script>

<template>
  <!--
    标题走 titleKey（外壳只认 key —— pages.json 的静态标题切语言不会变）。
    商品名放页内第一行：它比「库存明细」这四个字更能告诉人此刻在看哪件货。
  -->
  <sh-scaffold title-key="stockDetail.title" :denied="!merchant.can('biz:stock')">
    <template v-if="detail">
      <text class="txt-title head">{{ detail.name }}{{ detail.specText ? ` · ${detail.specText}` : "" }}</text>

      <sh-stat
        :items="[
          { value: detail.onHand, label: String($t('stockDetail.onHand')) },
          { value: detail.reserved, label: String($t('stockDetail.reserved')), tone: 'warn' },
          { value: detail.available, label: String($t('stockDetail.available')) },
        ]"
      ></sh-stat>

      <view class="sh-card">
        <text class="txt-caption">
          {{ $t("stockDetail.idLine", {
            barcode: detail.barcode || "—",
            code: detail.itemCode || "—",
            uom: uomLabel(detail.baseUom, t) || "—",
          }) }}
        </text>
      </view>

      <!--
        `sh-section` 是**块的标题行**（flex + space-between，右侧留给动作），
        不是容器 —— 内容要放它后面，套在 `sh-block` 里。
        当成容器用的话，几行内容会横着挤在标题右边。
      -->
      <!--
        安全库存单独一块，摆在「按库位」之前：它是**这件货的一个设置**，
        与下面那块「这件货在哪儿有多少」不是一回事。
        合进去的话，多库位商家会看到一行阈值夹在几行数量中间，分不清哪行可点。
      -->
      <view class="sh-block">
        <view class="blk">
          <sh-kv between :label="String($t('stockDetail.safety'))" @tap="editSafety()">
            <text class="sh-link sh-num">{{ safetyText(detail.safetyStock) }}</text>
          </sh-kv>
          <text class="sh-hint">{{ $t("stockDetail.safetyHint") }}</text>
        </view>
      </view>

      <view class="sh-block">
        <sh-section pad :title="String($t('stockDetail.byLocation'))"></sh-section>
        <view class="blk">
          <!--
            阈值那一列**只在多于一个库位时出现**。一个库位的商家（绝大多数）
            用不到覆盖，摆出来只会让人以为有两个数要维护。
          -->
          <sh-kv
            v-for="l in detail.byLocation"
            :key="l.locationId"
            between
            :label="l.locationName"
            @tap="detail.byLocation.length > 1 ? editSafety(l) : undefined"
          >
            <text class="sh-num">{{ l.onHand }}</text>
            <text v-if="detail.byLocation.length > 1" class="sh-muted loc__safety">
              {{ safetyText(l.safetyStock) }}
            </text>
          </sh-kv>
        </view>
      </view>

      <view class="sh-block">
        <sh-section pad :title="String($t('stockDetail.ledger'))"></sh-section>
        <view class="blk">
        <sh-empty v-if="!ledger.length" compact :text="String($t('stockDetail.ledgerEmpty'))"></sh-empty>
        <text v-else class="sh-hint led__hint">{{ $t("stockDetail.ledgerHint") }}</text>

        <view v-for="r in ledger" :key="r.id" class="led" @tap="openDoc(r)">
          <view class="sh-fill">
            <text class="txt-strong led__title">{{ $t(`stock.reason.${r.reasonCode}`) }}</text>
            <view class="led__meta">
              <!-- 单号用链接色：这一行可点，而「可点」在密排的流水里得看得出来 -->
              <text class="sh-link sh-num">{{ r.docNo }}</text>
              <text class="sh-muted">{{ r.operator || "—" }}</text>
            </view>
            <text class="txt-caption sh-num">{{ at(r.occurredAt) }}</text>
          </view>
          <view class="led__end">
            <!-- 带符号显示：「2」与「−2」在窄列里差一个字符，加了号才不用回头看单据类型 -->
            <text class="txt-strong sh-num" :class="r.qtyDelta < 0 ? 'is-danger' : 'is-success'">
              {{ r.qtyDelta > 0 ? `+${r.qtyDelta}` : r.qtyDelta }}
            </text>
            <text class="txt-caption sh-num">{{ before(r) }} → {{ r.balanceAfter }}</text>
          </view>
        </view>

        <text v-if="cursor != null" class="sh-link led__more" @tap="more">
          {{ $t("stockDetail.more") }}
        </text>
        </view>
      </view>

      <!--
        改数是这一页唯一的写动作，放页尾整宽。**不做成导航栏右上角的小字** ——
        它会改库存并落一张单，而右上角那个位置在别的页面是「保存草稿」那种轻动作。
      -->
      <!-- 说明跟着按钮走：原来它在「差异原因」那张卡里，卡撤了它不能跟着没 -->
      <text class="sh-hint">{{ $t("stockDetail.adjustHint") }}</text>
      <view class="sh-btn" @tap="adjust">{{ $t("stockDetail.adjust") }}</view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 块内容的横向留白，与 `.sh-block__head` 取同一个 26rpx —— 对不齐的话，
   同一屏上标题与内容会有两种缩进 */
.blk {
  padding: 0 26rpx 8rpx;
}
.head {
  display: block;
}
/* 阈值跟在数量后面。**用 margin 不用 gap** —— sh-kv 的右侧槽位没有 flex 容器，
   两个 <text> 会贴在一起变成「40不预警」 */
.loc__safety {
  margin-inline-start: 16rpx;
}
.led {
  display: flex;
  gap: 20rpx;
  align-items: flex-start;
  padding: 12rpx 0;
}
.led + .led {
  border-top: var(--sh-hairline-soft);
}

.led__title {
  display: block;
}
.led__meta {
  display: flex;
  gap: 20rpx;
  margin-top: 8rpx;
}
.led__end {
  text-align: end;
  flex: none;
}
/* 同 stock 页：<text> 默认 inline，不转 block 会挤成「−25 → 3」 */
.led__end > text {
  display: block;
}
.led__hint {
  display: block;
  padding-bottom: 4rpx;
}
.led__more {
  display: block;
  text-align: center;
  padding: 16rpx 0;
}
</style>
