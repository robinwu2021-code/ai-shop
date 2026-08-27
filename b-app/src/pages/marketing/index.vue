<script setup lang="ts">
// 营销活动（B-11.8）。四类活动一套模型：店铺券 / 满减 / 限时特价 / 买赠。
//
// 为什么合成一个页面而不是四个：它们在数据上只差「触发条件 + 优惠方式」。
// 各做一套的结果是四份几乎一样的增删改查，以及四份互不知情的叠加规则 ——
// 而叠加恰恰是最容易算错、也最容易被用户拿来薅的地方。
//
// 三条护栏（都在 mock/后端强制，不靠页面自觉）：
//   · 店铺券必须设发放总量 —— 不设上限等于开着口子发钱
//   · 限时特价必须选商品 —— 全店改价那叫调价，走商品编辑
//   · 已结束的活动不能复活、不能改 —— 时段已过，打开只会立刻又结束
import { computed, ref } from "vue";
import { onShow } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { money, toMinor } from "@shared/utils/money";
import { monthDay } from "@shared/utils/datetime";
import { useMerchantStore } from "@/stores/merchant";
import type { CampaignType, Goods, MarketingCampaign } from "@shared/types";
import { pick } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

const TYPES: CampaignType[] = ["COUPON", "FULL_CUT", "FLASH", "BUY_GIFT"];
const DAY = 86400_000;

const list = ref<MarketingCampaign[]>([]);
const goods = ref<Goods[]>([]);
const editing = ref(false);
const saving = ref(false);

const form = ref({
  campaignNo: "",
  type: "FULL_CUT" as CampaignType,
  name: "",
  days: "7",
  threshold: "",
  discount: "",
  flashPrice: "",
  buyN: "2",
  giftM: "1",
  totalCount: "100",
  goodsNos: [] as string[],
  /** 空 = 全主体。只有满减能限定门店 —— 见 need.store 的说明 */
  storeNo: "",
});

/** 每类活动只显示自己用得上的字段 —— 一个表单塞满 8 个输入框没人填得完 */
const need = computed(() => ({
  threshold: form.value.type === "COUPON" || form.value.type === "FULL_CUT",
  discount: form.value.type === "COUPON" || form.value.type === "FULL_CUT",
  flashPrice: form.value.type === "FLASH",
  buyGift: form.value.type === "BUY_GIFT",
  total: form.value.type === "COUPON",
  goods: form.value.type === "FLASH" || form.value.type === "BUY_GIFT",
  /*
   * 限定门店**只对满减开放**，且只在真的有多家店时才显示。
   *
   * 判据是活动在哪一刻生效：满减在算价时生效，那时顾客已经选好自提点，
   * 货从哪家店出是确定的。限时特价与买赠改的是**商品页的展示**（活动价、赠品标），
   * 而顾客浏览商品时还没选自提点 —— 允许限定门店就会出现
   * 「页面显示 ¥9.90、下单变 ¥12.80」。
   */
  store: form.value.type === "FULL_CUT" && merchant.multiStore,
}));

/** 门店号 → 门店名。查不到就原样显示号，空白比一个号更难查 */
function storeName(storeNo?: string) {
  if (!storeNo) return t("marketing.allStores");
  return merchant.stores.find((s) => s.storeNo === storeNo)?.name ?? storeNo;
}

/** 选门店。第一项是「全部门店」= 不限定 */
async function pickStore() {
  const usable = merchant.stores.filter((x) => x.status === "ACTIVE");
  const idx = await pick({
    items: [String(t("marketing.allStores")), ...usable.map((x) => x.name || x.storeNo)],
    selected: form.value.storeNo
      ? usable.findIndex((x) => x.storeNo === form.value.storeNo) + 1 : 0,
  });
  if (idx === null) return;
  form.value.storeNo = idx === 0 ? "" : (usable[idx - 1]?.storeNo ?? "");
}

async function load() {
  const [cs, gs] = await Promise.all([api.mCampaignList(), api.mGoodsList({ size: 100 })]);
  list.value = cs;
  goods.value = gs.records;
}

function goActivities() {
  uni.navigateTo({ url: "/pages/activities/index" });
}

function goCoupons() {
  uni.navigateTo({ url: "/pages/coupons/index" });
}

function startNew() {
  editing.value = true;
  form.value = {
    campaignNo: "",
    type: "FULL_CUT",
    name: "",
    days: "7",
    threshold: "",
    discount: "",
    flashPrice: "",
    buyN: "2",
    giftM: "1",
    totalCount: "100",
    goodsNos: [],
    storeNo: "",
  };
}

function toggleGoods(goodsNo: string) {
  const i = form.value.goodsNos.indexOf(goodsNo);
  if (i >= 0) form.value.goodsNos.splice(i, 1);
  else form.value.goodsNos.push(goodsNo);
}

async function save() {
  if (!form.value.name.trim()) {
    uni.showToast({ title: t("marketing.needName"), icon: "none" });
    return;
  }
  if (saving.value) return;
  saving.value = true;
  try {
    const startAt = Date.now();
    await api.mSaveCampaign({
      campaignNo: form.value.campaignNo || undefined,
      type: form.value.type,
      name: form.value.name.trim(),
      startAt,
      endAt: startAt + (Number(form.value.days) || 1) * DAY,
      thresholdMinor: need.value.threshold ? toMinor(form.value.threshold || "0") : undefined,
      discountMinor: need.value.discount ? toMinor(form.value.discount || "0") : undefined,
      flashPriceMinor: need.value.flashPrice ? toMinor(form.value.flashPrice || "0") : undefined,
      buyN: need.value.buyGift ? Number(form.value.buyN) : undefined,
      giftM: need.value.buyGift ? Number(form.value.giftM) : undefined,
      totalCount: need.value.total ? Number(form.value.totalCount) : undefined,
      goodsNos: form.value.goodsNos,
      // 切成别的类型后残留的门店选择要清掉 —— 否则后端会以 70005 拒掉，
      // 而商家看到的是一个他早已改过的选项在报错
      storeNo: need.value.store && form.value.storeNo ? form.value.storeNo : undefined,
    });
    editing.value = false;
    uni.showToast({ title: t("common.saved"), icon: "none" });
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    saving.value = false;
  }
}

async function toggle(c: MarketingCampaign) {
  try {
    await api.mToggleCampaign(c.campaignNo, c.status !== "RUNNING");
    await load();
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  }
}

/** 一句话说清这个活动是什么，比让店主自己看四个字段强 */
function summary(c: MarketingCampaign): string {
  if (c.type === "COUPON") {
    return t("marketing.sumCoupon", {
      a: money(c.thresholdMinor ?? 0),
      b: money(c.discountMinor ?? 0),
    });
  }
  if (c.type === "FULL_CUT") {
    return t("marketing.sumFullCut", {
      a: money(c.thresholdMinor ?? 0),
      b: money(c.discountMinor ?? 0),
    });
  }
  if (c.type === "FLASH") {
    return t("marketing.sumFlash", { a: money(c.flashPriceMinor ?? 0), n: c.goodsNos.length });
  }
  return t("marketing.sumBuyGift", { n: c.buyN ?? 0, m: c.giftM ?? 0 });
}

onShow(load);
</script>

<template>
  <sh-scaffold title-key="marketing.title" :denied="!merchant.can('biz:campaign')">
    <view class="head">
      <text class="txt-display">{{ $t("marketing.title") }}</text>
      <text v-if="!editing" class="sh-link" @tap="startNew">{{ $t("marketing.create") }}</text>
    </view>

    <!--
      优惠券是另一套东西（P4 新模型）：活动作用在**这一单**上，券作用在**这个人**上。
      放一个入口而不是并进上面的四类，是因为券要选人群、要算敞口、要看跳过明细 ——
      塞进活动表单里会让这一页变成两页拼起来的样子。
    -->
    <!-- 新模型的活动（P5）。老的四类活动还在下面，两套并存到旧表退场 -->
    <view v-if="!editing" class="entry" @tap="goActivities">
      <view class="entry__main">
        <text class="entry__t">{{ $t("activities.title") }}</text>
        <text class="sh-muted entry__d">{{ $t("marketing.activityEntryHint") }}</text>
      </view>
      <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
    </view>

    <view v-if="!editing" class="entry" @tap="goCoupons">
      <view class="entry__main">
        <text class="entry__t">{{ $t("coupons.title") }}</text>
        <text class="sh-muted entry__d">{{ $t("marketing.couponEntryHint") }}</text>
      </view>
      <sh-icon name="chevronRight" :size="18" color="var(--sh-sub)"></sh-icon>
    </view>

    <!-- 新建表单 -->
    <view v-if="editing" class="sh-card sh-mt-sm">
      <view class="field">
        <text class="field__label">{{ $t("marketing.type") }}</text>
        <view class="chips">
          <text
            v-for="ty in TYPES"
            :key="ty"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.type === ty }"
            @tap="form.type = ty"
          >
            {{ $t(`marketing.type${ty}`) }}
          </text>
        </view>
        <text class="sh-muted hint">{{ $t(`marketing.desc${form.type}`) }}</text>
      </view>

      <view class="field">
        <text class="field__label">{{ $t("marketing.name") }}</text>
        <input maxlength="64" v-model="form.name" class="field__input" :placeholder="$t('marketing.namePh')" />
      </view>

      <view class="field">
        <text class="field__label">{{ $t("marketing.days") }}</text>
        <input maxlength="4" v-model="form.days" class="field__input sh-num" type="number" />
      </view>

      <!--
        限定门店。只有满减 + 多门店时出现 —— 单店商家看到「适用门店」只会疑惑，
        而另外三种类型限定门店会让页面价与下单价打架（后端也会拒）。
      -->
      <view v-if="need.store" class="field" @tap="pickStore">
        <text class="field__label">{{ $t("marketing.store") }}</text>
        <view class="field__input field__input--pick">
          <text>{{ storeName(form.storeNo) }}</text>
          <sh-icon name="chevronRight" :size="20" color="var(--sh-sub)"></sh-icon>
        </view>
      </view>

      <view v-if="need.threshold" class="field">
        <text class="field__label">{{ $t("marketing.threshold") }}</text>
        <input maxlength="10" v-model="form.threshold" class="field__input sh-num" type="digit" />
      </view>
      <view v-if="need.discount" class="field">
        <text class="field__label">{{ $t("marketing.discount") }}</text>
        <input maxlength="10" v-model="form.discount" class="field__input sh-num" type="digit" />
      </view>
      <view v-if="need.flashPrice" class="field">
        <text class="field__label">{{ $t("marketing.flashPrice") }}</text>
        <input maxlength="10" v-model="form.flashPrice" class="field__input sh-num" type="digit" />
      </view>
      <view v-if="need.buyGift" class="field">
        <text class="field__label">{{ $t("marketing.buyGift") }}</text>
        <view class="sh-row">
          <input maxlength="6" v-model="form.buyN" class="field__input sh-num flex1" type="number" />
          <text class="sh-muted">{{ $t("marketing.buyGiftMid") }}</text>
          <input maxlength="6" v-model="form.giftM" class="field__input sh-num flex1" type="number" />
        </view>
      </view>
      <view v-if="need.total" class="field">
        <text class="field__label">{{ $t("marketing.totalCount") }}</text>
        <input maxlength="6" v-model="form.totalCount" class="field__input sh-num" type="number" />
        <text class="sh-muted hint">{{ $t("marketing.totalHint") }}</text>
      </view>

      <view v-if="need.goods" class="field">
        <text class="field__label">{{ $t("marketing.goods") }}</text>
        <view class="chips">
          <text
            v-for="g in goods"
            :key="g.goodsNo"
            class="sh-chip"
            :class="{ 'sh-chip--primary': form.goodsNos.includes(g.goodsNo) }"
            @tap="toggleGoods(g.goodsNo)"
          >
            {{ g.title }}
          </text>
        </view>
      </view>

      <view class="btns">
        <text class="btn btn--ghost" @tap="editing = false">{{ $t("common.cancel") }}</text>
        <text class="btn" @tap="save">{{ $t("common.save") }}</text>
      </view>
    </view>

    <!-- 活动列表 -->
    <sh-empty v-if="!list.length && !editing" :text='$t("marketing.empty")'></sh-empty>

    <view v-for="c in list" :key="c.campaignNo" class="sh-card sh-mt-sm">
      <view class="item__head">
        <text class="item__name">{{ c.name }}</text>
        <text
          class="sh-chip"
          :class="{
            'sh-chip--primary': c.status === 'RUNNING',
            'sh-chip--warning': c.status === 'PAUSED',
          }"
        >
          {{ $t(`marketing.status${c.status}`) }}
        </text>
      </view>
      <text class="sh-muted item__sum">{{ summary(c) }}</text>
      <!-- 多店商家必须看得见这条活动是哪家店的 —— 否则两条同名的「开业满减」分不清 -->
      <text v-if="merchant.multiStore" class="sh-muted item__sum">
        {{ $t("marketing.store") }}：{{ storeName(c.storeNo) }}
      </text>
      <view class="item__meta">
        <text class="sh-muted sh-num">{{ monthDay(c.startAt) }} – {{ monthDay(c.endAt) }}</text>
        <text v-if="c.type === 'COUPON'" class="sh-muted sh-num">
          {{ $t("marketing.taken", { a: c.takenCount ?? 0, b: c.totalCount ?? 0 }) }}
        </text>
        <text class="sh-muted sh-num">{{ $t("marketing.used", { n: c.usedCount }) }}</text>
      </view>
      <text v-if="c.status !== 'ENDED'" class="sh-link act" @tap="toggle(c)">
        {{ c.status === "RUNNING" ? $t("marketing.pause") : $t("marketing.resume") }}
      </text>
    </view>

    <text class="tip sh-hint">{{ $t("marketing.stackHint") }}</text>
  </sh-scaffold>
</template>

<style scoped>
.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--sh-surface);
  border-radius: 16rpx;
  padding: 24rpx;
  margin-top: 16rpx;
}
.entry__t {
  font-size: 28rpx;
  font-weight: 600;
}
.entry__d {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.flex1 {
  flex: 1;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.chips .sh-chip {
  font-size: 24rpx;
  padding: 14rpx 24rpx;
}
.hint {
  display: block;
  margin-top: 12rpx;
  line-height: 1.6;
}
.btns {
  display: flex;
  gap: 16rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 28rpx;
  font-weight: 600;
}
.btn--ghost {
  background: var(--sh-faint);
  color: var(--sh-sub);
}

.item__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}
.item__name {
  flex: 1;
  font-size: 30rpx;
  font-weight: 400;
  color: var(--sh-ink);
}
.item__sum {
  display: block;
  margin-top: 10rpx;
}
.item__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  margin-top: 16rpx;
}
.act {
  display: inline-block;
  margin-top: 20rpx;
}
.tip {
  margin: 32rpx 8rpx;
}

/* 假选择器：长得像输入框，点开是弹层。**这一页只有一处，所以修饰留在页内** ——
   只有一个调用点的东西进 base.css，等于把页面样式搬了个家。
   `.field__input` 是 88rpx 高的盒子，这里只补「文字在左、箭头在右」。
   此前箭头是贴在文本尾巴上的一个 `›` 字符：**字符不是图标** ——
   它跟着字体走（不同端字形不一样）、拿不到 sh-icon 的尺寸与颜色档，
   也没法只给它上色而不动文字。 */
.field__input--pick {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
