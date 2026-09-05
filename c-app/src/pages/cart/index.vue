<script setup lang="ts">
// 购物车：按履约方式分组（自提 / 快递 / 到店核销），**勾选的那一组一单**。
//
// 两个模式共用同一批勾选框：
//   · 普通态 —— 勾的是「这几件我要买」，底栏是合计 + 去结算
//   · 编辑态 —— 勾的是「这几件我要删」，底栏换成删除
// 两套勾选在 store 里是**两个字段**（`selected` / `marked`），不是同一个：
// 合成一套的话，删完东西回到普通态，结算勾选会莫名其妙变成刚才为了删而点的那些。
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { onShow } from "@dcloudio/uni-app";
import { useCartStore } from "@/stores/cart";
import { money } from "@shared/utils/format";
import { CART_RULES, ROUTES } from "@shared/utils/constants";
import type { CartGroup, MerchantSegment } from "@/stores/cart";
import type { CartItem, FulfillmentType } from "@shared/types";
import { confirm, prompt } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const cart = useCartStore();

/** 编辑态。进出都把上一次的标记清掉 —— 它是一次操作，不该留到下一次 */
const editing = ref(false);
function toggleEdit() {
  cart.clearMarks();
  editing.value = !editing.value;
}

/**
 * 这一行的上限件数。`null` = 后端没给可售库存，端上不设限。
 *
 * **不能把「没给」当成 0**：旧版本后端与 mock 都可能不发这个字段，
 * 当成 0 的话整车一件都加不了，而且只在那些环境里才出现。
 */
function maxOf(it: CartItem): number | null {
  return typeof it.available === "number" ? it.available : null;
}
function atMax(it: CartItem): boolean {
  const max = maxOf(it);
  return max !== null ? it.qty >= max : it.qty >= CART_RULES.maxQtyPerLine;
}
/** 库存快没了才提醒。有货时报数字是噪音，只有「快没了」才改变他的决定 */
function lowStock(it: CartItem): boolean {
  const max = maxOf(it);
  return max !== null && max > 0 && max <= CART_RULES.lowStockHint;
}

/**
 * 不可售的原因。**端上按 `invalid` / `available` 两个事实组装本地化文案** ——
 * 让后端发那句中文原因等于把文案钉死在服务端，而这个 app 有三门语言。
 */
function invalidText(it: CartItem): string {
  return String(it.invalid ? t("cart.invalidOffShelf") : t("cart.invalidSoldOut"));
}

// ── 勾选 ──────────────────────────────────────────────────────────────

function boxOn(it: CartItem): boolean {
  return editing.value ? cart.isMarked(it.skuNo) : cart.isSelected(it.skuNo);
}

/** 让位时说一句。静默切换会让人以为刚才勾的那几件还在 */
function saySwitched(to: FulfillmentType) {
  uni.showToast({
    title: String(t("cart.oneFulfillmentOnly", { name: String(t(`fulfillment.${to}`)) })),
    icon: "none",
  });
}

function tapItem(it: CartItem) {
  if (editing.value) {
    cart.toggleMark(it.skuNo);
    return;
  }
  if (cart.toggle(it.skuNo)) saySwitched(it.fulfillment);
}
function tapGroup(g: CartGroup) {
  const on = !g.items.every((it) => cart.isSelected(it.skuNo));
  if (cart.setGroup(g.fulfillment, on)) saySwitched(g.fulfillment);
}
function tapMerchant(g: CartGroup, m: MerchantSegment) {
  const on = !m.items.every((it) => cart.isSelected(it.skuNo));
  if (cart.setMerchant(g.fulfillment, m.merchantNo, on)) saySwitched(g.fulfillment);
}
function groupOn(g: CartGroup): boolean {
  return g.items.length > 0 && g.items.every((it) => cart.isSelected(it.skuNo));
}
function merchantOn(m: MerchantSegment): boolean {
  return m.items.length > 0 && m.items.every((it) => cart.isSelected(it.skuNo));
}

const allOn = computed(() => (editing.value ? cart.allMarked : cart.allSelected));
function tapAll() {
  if (editing.value) cart.setAllMarked(!cart.allMarked);
  else cart.setAllInActive(!cart.allSelected);
}

// ── 数量 ──────────────────────────────────────────────────────────────

/**
 * 减到 1 就停住。**减号的语义是减数量，不是删除** ——
 * 此前它在 qty=1 时传 0 下去，而后端与 mock 都把 `qty<=0` 当删除：
 * 商品当场消失，没有任何确认，也没有撤销。删除有它自己的入口（编辑态）。
 */
function dec(it: CartItem) {
  if (it.qty <= 1) return;
  void cart.update(it.skuNo, it.qty - 1);
}
function inc(it: CartItem) {
  if (atMax(it)) return;
  void cart.update(it.skuNo, it.qty + 1);
}

/** 点数字直接输入。一次买 20 件不该点 19 下加号 */
async function askQty(it: CartItem) {
  const max = maxOf(it);
  const v = await prompt({
    title: String(t("cart.qtyTitle")),
    hint: String(max !== null ? t("cart.qtyHint", { n: max }) : t("cart.qtyHintFree")),
    value: String(it.qty),
    type: "number",
    maxlength: 4,
  });
  if (v === null) return;
  const n = Math.floor(Number(v));
  // 输 0 或乱输不当删除：那是另一个动作，得他自己去点
  if (!Number.isFinite(n) || n <= 0) return;
  const capped = Math.min(n, max ?? CART_RULES.maxQtyPerLine);
  if (capped === it.qty) return;
  void cart.update(it.skuNo, capped);
}

// ── 删除 ──────────────────────────────────────────────────────────────

/**
 * 删掉一件不可售的。**只删这一件**，不顺手清空所有不可售的 ——
 * 用户可能只是想去掉其中一件、把别的留着等它恢复（换回原来的位置就又能买了）。
 */
async function remove(skuNo: string) {
  await cart.remove([skuNo]);
}

/** 编辑态的批量删。**是他勾出来的，不是替他清理** */
async function removeMarked() {
  const n = cart.marked.length;
  /*
   * **灰按钮也要说话。** 同一页的「去结算」在一件没勾时会 toast 一句，
   * 这里此前是静默 return —— 同一个页面两套规矩，而规矩本身写在
   * 交互清单 §七：灰按钮必须配一句话。
   */
  /*
   * **灰按钮也要说话。** 同一页的「去结算」在一件没勾时会 toast 一句，
   * 这里此前是静默 return —— 同一个页面两套规矩，而规矩本身写在
   * 交互清单 §七：灰按钮必须配一句话。
   */
  if (!n) {
    uni.showToast({ title: String(t("cart.pickToRemove")), icon: "none" });
    return;
  }
  const ok = await confirm({
    title: String(t("cart.removeTitle", { n })),
    hint: String(t("cart.removeHint")),
    confirmText: String(t("cart.remove")),
    danger: true,
  });
  if (!ok) return;
  await cart.removeMarked();
  if (!cart.items.length) editing.value = false;
}

// ── 去结算 ────────────────────────────────────────────────────────────

/**
 * 一组一单：勾选被约束在同一种履约方式里，所以这里不用再问一次。
 *
 * 此前多组时会先弹一个动作面板让他挑一组 —— 那是把内部的「一组一单」
 * 直接暴露成一次额外点击。现在他勾的是什么就结什么，底栏的合计
 * **永远等于下一页的应付**。
 */
function checkout() {
  const items = cart.selectedItems;
  const fulfillment = cart.activeFulfillment;
  if (!items.length || !fulfillment) {
    uni.showToast({ title: String(t("cart.pickSomething")), icon: "none" });
    return;
  }
  const skus = items.map((i) => i.skuNo).join(",");
  uni.navigateTo({
    url: `${ROUTES.orderConfirm}?fulfillment=${fulfillment}&skus=${skus}`,
  });
}

function openGoods(it: CartItem) {
  if (editing.value) return;
  uni.navigateTo({ url: `${ROUTES.goods}?goodsNo=${it.goodsNo}` });
}
function goShopping() {
  uni.switchTab({ url: ROUTES.home });
}

onShow(() => cart.load());
</script>

<template>
  <sh-scaffold title-key="cart.title" tab="cart">
    <!-- 件数与编辑入口。小程序端导航栏是原生的，放不进去，所以在页内起一行 -->
    <view v-if="cart.items.length" class="topbar sh-row sh-row--between">
      <text class="txt-caption sh-num">{{ $t("cart.itemsCount", { n: cart.count }) }}</text>
      <text class="sh-link" @tap="toggleEdit">
        {{ editing ? $t("cart.editDone") : $t("cart.edit") }}
      </text>
    </view>

    <view v-for="g in cart.groups" :key="g.fulfillment" class="sh-card">
      <view class="ghead sh-row">
        <!-- 勾选由外层的大点击区接管，sh-check 只负责画（与 b 端四处同一写法） -->
        <view v-if="!editing" class="box sh-center" @tap="tapGroup(g)">
          <sh-check :model-value="groupOn(g)"></sh-check>
        </view>
        <text class="sh-chip sh-chip--primary">{{ $t(`fulfillment.${g.fulfillment}`) }}</text>
        <!-- 多组时才说这句：只有一组时它是句废话 -->
        <text v-if="cart.groups.length > 1" class="txt-caption ghead__note">
          {{ $t("cart.oneFulfillmentHint") }}
        </text>
      </view>

      <!--
        商家段。**一段 = 结算后的一笔子订单** —— 用户要在提交前看见会拆成几单。
        只有一家店时不画段头：一家店还套个分组框是纯噪音。
      -->
      <template v-for="m in g.merchants" :key="m.merchantNo">
        <view v-if="g.merchants.length > 1" class="seg sh-row">
          <view v-if="!editing" class="box sh-center" @tap="tapMerchant(g, m)">
            <sh-check :model-value="merchantOn(m)"></sh-check>
          </view>
          <text class="txt-strong">{{ m.merchantName || $t("cart.unknownMerchant") }}</text>
        </view>

        <view v-for="it in m.items" :key="it.skuNo" class="line sh-row">
          <view class="box sh-center" @tap.stop="tapItem(it)">
            <sh-check :model-value="boxOn(it)"></sh-check>
          </view>
          <biz-sku-row
            class="sh-fill"
            :cover="it.cover"
            :title="it.title"
            :spec="it.spec"
            size="lg"
            @tap="openGoods(it)"
          >
            <view v-if="it.giftQty" class="giftrow sh-row">
              <text class="txt-caption giftrow__tag">{{ $t("promo.gift") }}</text>
              <text class="txt-caption giftrow__text sh-num">
                {{ $t("promo.giftItem", { title: it.title, n: it.giftQty }) }}
              </text>
            </view>

            <view class="row__foot sh-row sh-row--between">
              <view class="sh-fill">
                <text class="txt-price sh-num">{{ money(it.price) }}</text>
                <!-- 库存快没了才说。有货时报数字是噪音 -->
                <text v-if="lowStock(it)" class="txt-caption line__stock sh-num">
                  {{ $t("cart.stockLeft", { n: it.available }) }}
                </text>
              </view>
              <view class="stepper sh-row" @tap.stop>
                <view
                  class="stepper__btn sh-center"
                  :class="{ 'is-off': it.qty <= 1 }"
                  @tap.stop="dec(it)"
                >
                  <sh-icon name="minus" :size="26" color="var(--sh-ink)"></sh-icon>
                </view>
                <text class="txt-strong stepper__num sh-num" @tap.stop="askQty(it)">{{ it.qty }}</text>
                <view
                  class="stepper__btn sh-center"
                  :class="{ 'is-off': atMax(it) }"
                  @tap.stop="inc(it)"
                >
                  <sh-icon name="plus" :size="26" color="var(--sh-ink)"></sh-icon>
                </view>
              </view>
            </view>
          </biz-sku-row>
        </view>
      </template>

      <!-- 会拆几单，说在提交之前。放在提交之后就只剩解释作用了（C-OD-06 的意图前移） -->
      <text v-if="g.merchants.length > 1" class="txt-caption splitnote">
        {{ $t("cart.splitNote", { n: g.merchants.length }) }}
      </text>
    </view>

    <!--
      **不可售单独成区，而且要说出是为什么。**
      后端一直在标（下架、售罄），而这一页此前一处都没展示 ——
      更糟的是 `groups` 只遍历有效件，那段模板根本渲染不到：
      货不是「灰着躺在车里」，是**凭空消失**，而底栏的件数还算着它。
      **不自动删**：那是他的东西，删不删由他决定。
    -->
    <view v-if="cart.invalidItems.length" class="sh-card">
      <view class="ghead sh-row">
        <text class="txt-strong">{{ $t("cart.invalidTitle") }}</text>
        <text class="txt-caption ghead__note sh-num">{{ cart.invalidItems.length }}</text>
      </view>
      <view v-for="it in cart.invalidItems" :key="it.skuNo" class="line sh-row is-invalid">
        <view v-if="editing" class="box sh-center" @tap.stop="cart.toggleMark(it.skuNo)">
          <sh-check :model-value="cart.isMarked(it.skuNo)"></sh-check>
        </view>
        <biz-sku-row
          class="sh-fill"
          :cover="it.cover"
          :title="it.title"
          :spec="it.spec"
          size="lg"
        >
          <view class="invalid sh-row sh-row--between">
            <text class="txt-caption txt-ink">{{ invalidText(it) }}</text>
            <text class="sh-link" @tap.stop="remove(it.skuNo)">
              {{ $t("cart.removeInvalid") }}
            </text>
          </view>
        </biz-sku-row>
      </view>
    </view>

    <!--
      空态**要等第一次拉完**。不等的话冷启动那一瞬间「购物车是空的」会先闪一下，
      再被商品顶掉 —— 看起来像刚被谁清空了。
    -->
    <view v-if="cart.loaded && !cart.items.length" class="sh-card empty">
      <text class="txt-strong empty__t">{{ $t("cart.empty") }}</text>
      <text class="txt-caption empty__d">{{ $t("cart.emptyHint") }}</text>
      <view class="sh-btn sh-btn--sm empty__btn" @tap="goShopping">
        {{ $t("cart.goShopping") }}
      </view>
    </view>

    <sh-actionbar v-if="cart.items.length" pill="lead" tabbar :pad="140">
      <!-- 光一个勾选框说不清它管的是什么，配一个字 —— 它离商品行有一段距离 -->
      <view class="bar__all sh-row" @tap="tapAll">
        <view class="box sh-center">
          <sh-check :model-value="allOn"></sh-check>
        </view>
        <text class="txt-caption">{{ $t("cart.selectAll") }}</text>
      </view>
      <template v-if="editing">
        <view class="sh-fill">
          <text class="txt-strong">{{ $t("cart.itemsCount", { n: cart.marked.length }) }}</text>
        </view>
        <view
          class="txt-body sh-btn sh-btn--danger bar__btn"
          :class="{ 'is-disabled': !cart.marked.length }"
          @tap="removeMarked"
        >
          {{ $t("cart.remove") }}
        </view>
      </template>
      <template v-else>
        <view class="sh-fill">
          <!-- 运费在下一页按地址算。不说这句，底栏与应付对不上会被当成算错了 -->
          <text class="sh-muted bar__note">{{ $t("cart.total") }}</text>
          <text class="txt-price bar__total sh-num">{{ money(cart.selectedTotalFen) }}</text>
        </view>
        <view
          class="txt-body sh-btn bar__btn"
          :class="{ 'is-disabled': !cart.selectedCount }"
          @tap="checkout"
        >
          {{ $t("cart.checkout") }}<text v-if="cart.selectedCount" class="sh-num"> ({{ cart.selectedCount }})</text>
        </view>
      </template>
    </sh-actionbar>
  </sh-scaffold>
</template>

<style scoped>
.topbar {
  padding: 0 8rpx;
}

/* 勾选框的点击区。**44rpx 的框只有 22px 见方，手指按不准** ——
   外面套一个 72rpx 的透明区，画出来的还是那个框 */
.box {
  flex: none;
  width: 72rpx;
  height: 72rpx;
  margin-inline-start: -14rpx;
}

.ghead {
  gap: 12rpx;
}
.ghead__note {
  margin-inline-start: auto;
}

.seg {
  gap: 12rpx;
  margin: 24rpx 0 8rpx;
}

.line {
  align-items: center;
  gap: 8rpx;
}
.line + .line {
  margin-top: 24rpx;
}
/* 段头之后的第一行也要留缝：`.line + .line` 只管相邻两行 */
.seg + .line,
.ghead + .line {
  margin-top: 16rpx;
}
.line__stock {
  display: block;
  color: var(--sh-warning);
}

/* 不可售的整行压暗，但**不隐藏** —— 用户要能看见自己加过什么 */
.is-invalid {
  opacity: 0.5;
}
.invalid {
  margin-top: 8rpx;
  padding: 10rpx 16rpx;
  /* 圆角走 token 五档，原先的 12rpx 不在档上 */
  border-radius: 16rpx;
  background: var(--sh-warning-tint);
}

.splitnote {
  display: block;
  margin-top: 16rpx;
}
.giftrow {
  gap: 12rpx;
  margin-top: 16rpx;
  background: var(--sh-danger-tint);
  border-radius: 16rpx;
  padding: 10rpx 16rpx;
}
.giftrow__tag {
  color: var(--sh-danger);
  flex-shrink: 0;
}
.giftrow__text {
  color: var(--sh-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row__foot {
  margin-top: 20rpx;
}

.stepper {
  flex: none;
  gap: 8rpx;
  background: var(--sh-faint);
  border-radius: 9999px;
  padding: 8rpx;
}
.stepper__btn {
  width: 52rpx;
  height: 52rpx;
  border-radius: 9999px;
  background: var(--sh-surface);
}
/* 到头了就明说，别让他反复点一个没反应的按钮 */
.stepper__btn.is-off {
  opacity: 0.35;
}
.stepper__num {
  min-width: 64rpx;
  text-align: center;
}

.empty {
  text-align: center;
  padding: 72rpx 24rpx;
}
.empty__t {
  display: block;
}
.empty__d {
  display: block;
  margin-top: 8rpx;
}
.empty__btn {
  display: inline-block;
  margin-top: 28rpx;
  padding-inline: 48rpx;
}

.bar__all {
  flex: none;
  gap: 4rpx;
  align-items: center;
  /* 药丸左内边距是 40rpx（给文字留的），这里是勾选框，往回收一点才与卡片里的框对齐 */
  margin-inline-start: -12rpx;
}
.bar__total,
.bar__note {
  display: block;
}
.bar__btn {
  flex: 0 0 auto;
  padding-inline: 44rpx;
}
</style>
