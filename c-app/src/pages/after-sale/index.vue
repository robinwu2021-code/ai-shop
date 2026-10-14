<script setup lang="ts">
// 售后申请。
// 生鲜的坏果包赔走的也是这条 —— 原因选「品质问题」+ 传图，小额自动通过（极速退）。
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onLoad } from "@dcloudio/uni-app";
import { api } from "@/api";
import { chooseImages } from "@shared/ports/media";
import { requestSubscribe, SUBSCRIBE_TMPL } from "@shared/ports/push";
import { ROUTES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import {
  applyGate, isFresh, needImage, orderStageOf, reasonsFor,
} from "@shared/strategies/after-sale";
import type { AfterSaleReason, AfterSaleType, Order } from "@shared/types";

const { t } = useI18n();

/**
 * 售后原因**取自后端**（`/mp/after-sale/reasons`），不再在端上硬编码。
 *
 * 此前这里写死了一份六个码的清单，而后端那份是七条且内容不同 ——
 * 两份各自漂移，运营改后端的，端上纹丝不动。
 * 拿不到时退回一份最小清单：售后入口不该因为一个列表接口挂掉而打不开。
 */
const FALLBACK: AfterSaleReason[] = ["DAMAGED", "MISSING", "QUALITY", "OTHER"];
const REASONS = ref<AfterSaleReason[]>(FALLBACK);

const order = ref<Order | null>(null);
/**
 * 售后类型默认「仅退款」—— 邻里生鲜绝大多数是坏了/少了，退回来没意义。
 * 退货退款是主动选择，不是默认路径。
 */
const type = ref<AfterSaleType>("REFUND_ONLY");
const TYPES: AfterSaleType[] = ["REFUND_ONLY", "RETURN_REFUND"];
/** 拼 key 后 `$t` 的类型收窄不住，包一层比在模板里写断言干净 */
const typeText = (tp: AfterSaleType, suffix = "") => String(t(`afterSale.type${tp}${suffix}`));
const reason = ref<AfterSaleReason | "">("");
const detail = ref("");
const images = ref<string[]>([]);
// 正在上传凭证图：「＋」变「…」并挡住重复点（真上传换 URL 的那一下，见 pickImages）
const uploadingImg = ref(false);
const submitting = ref(false);
const submitted = ref(false);

/**
 * 履约进度（货还拦不拦得住）与是否生鲜 —— 售后能不能退、要不要图片、原因过滤都按它（售后规则 §2.1/§2.2）。
 * 判据与后端 {@code FulfillStage}/{@code AfterSaleRules} 同一份（{@code @shared/strategies/after-sale}）。
 */
const stage = computed(() => (order.value ? orderStageOf(order.value) : "NOT_PREPARED"));
const fresh = computed(() => isFresh((order.value?.items ?? []).map((i) => i.type)));

/** 这一档可选的原因：生鲜已揽收/已签收只剩质量三项。与后端下发的清单取交集 —— 两边都收窄。 */
const shownReasons = computed(() => {
  const allowed = reasonsFor(stage.value, fresh.value);
  return REASONS.value.filter((r) => allowed.includes(r));
});

/** 已签收才显示图片上传框（未收货看不到实物损坏，传图没意义）。 */
const showImageBox = computed(() => needImage(stage.value));

/** 选中的原因若不在当前档位允许范围内，清掉 —— 否则换档后带着一个被禁的原因提交 */
watch([shownReasons, reason], () => {
  if (reason.value && !shownReasons.value.includes(reason.value)) {
    reason.value = "";
  }
});

const canSubmit = computed(() => !!reason.value && !submitting.value);

/** 已提交卡片的状态与文案：读售后单（order.afterSale），区分「待收货后退款」与「积分退款」。 */
const asStatus = computed(() => order.value?.afterSale?.status ?? null);
const refundedByPoints = computed(() => (order.value?.afterSale?.refundPoints ?? 0) > 0);
const doneHintKey = computed(() => {
  if (refundedByPoints.value) return "afterSale.refundedPoints";
  if (asStatus.value === "WAIT_RECEIPT") return "afterSale.waitReceipt";
  return asStatus.value === "REFUNDED" ? "afterSale.refundedHint" : "afterSale.appliedHint";
});

/** 这次没取到。**与「这个东西不存在」是两件事** —— 整页都挂在 `order` 后面，
 *  拉不到连外壳都不渲染，是一整块白屏：没有导航栏、没有一个字、退不回去 */
const failed = ref(false);
/** 重试要把单号带回去 —— `@retry` 不带参数 */
const currentNo = ref("");

async function load(orderNo: string) {
  currentNo.value = orderNo;
  try {
    order.value = await api.orderDetail(orderNo);
    failed.value = false;
  } catch {
    failed.value = true;
  }
}

async function pickImages() {
  let paths: string[];
  try {
    paths = await chooseImages(3 - images.value.length);
  } catch {
    return; // 用户取消，不提示
  }
  if (!paths.length) return;
  /*
   * **选图拿到的是端上临时路径，必须先上传换成服务器 URL 再进 list。**
   * 直接把本地路径塞进 images 提交，后端会存下 wxfile:// —— 提交回 200 看着像成功，
   * 但那路径换页 / 重开小程序就失效，图全裂（2026-10-10 店主反馈，查日志提交其实都成功了）。
   */
  uploadingImg.value = true;
  try {
    const urls = await Promise.all(paths.map((pth) => api.uploadImage(pth, "aftersale")));
    images.value = [...images.value, ...urls].slice(0, 3);
  } catch (e) {
    uni.showToast({ title: (e as Error).message || "图片上传失败，请重试", icon: "none" });
  } finally {
    uploadingImg.value = false;
  }
}

async function submit() {
  const o = order.value;
  if (!o || !canSubmit.value || !reason.value) return;
  /*
   * 灰色地带（生鲜已备货 / 已出单未揽收）：系统不自动放行也不硬拒，提交前先说清
   * 「商家可能已为你备货/打包，会核实后处理」，让买家心里有数（AC13）。
   * **这一步要在订阅授权与任何 await 之前** —— showModal 本身是 await，但它是用户主动触发的点击链路内，弹得出来。
   */
  if (applyGate(stage.value, fresh.value, reason.value).needMerchant) {
    const ok = await new Promise<boolean>((resolve) => {
      uni.showModal({
        title: String(t("afterSale.grayStageTitle")),
        content: String(t("afterSale.grayStageTip")),
        confirmText: String(t("afterSale.grayStageOk")),
        success: (r) => resolve(!!r.confirm),
        fail: () => resolve(false),
      });
    });
    if (!ok) return;
  }
  /*
   * 售后结果 / 退货寄回的订阅授权（TDD-微信订阅消息优先 AC7）。
   * 退款一律先审核（极速退已废除），所以都要订阅「结果」；退货退款再加「寄回」。
   */
  const tmpls = type.value === "RETURN_REFUND"
    ? [SUBSCRIBE_TMPL.afterSaleResult, SUBSCRIBE_TMPL.returnWait]
    : [SUBSCRIBE_TMPL.afterSaleResult];
  void requestSubscribe(tmpls).then((r) => {
    if (r.accepted.length) void api.subscribeReport(r.accepted, true);
    if (r.rejected.length) void api.subscribeReport(r.rejected, false);
  });
  submitting.value = true;
  try {
    const label = String(t(`afterSale.reason.${reason.value}`));
    // 返回的是售后单，不是订单 —— 赋给 order 会把这一页的商品与金额清空
    await api.applyAfterSale(
      o.orderNo,
      detail.value ? `${label}：${detail.value}` : label,
      images.value,
      type.value,
      reason.value || undefined,
    );
    await load(o.orderNo);
    submitted.value = true;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    submitting.value = false;
  }
}

function gotoOrder() {
  uni.redirectTo({ url: `${ROUTES.order}?orderNo=${order.value?.orderNo}` });
}

onLoad((q) => {
  // 原因清单由后端给；失败不挡住页面（FALLBACK 兜底）
  api.afterSaleReasons().then((r) => {
    if (r?.length) REASONS.value = r;
  }).catch(() => undefined);
  const no = (q?.orderNo as string) || "";
  if (no) load(no);
});
</script>

<template>
  <sh-scaffold title-key="afterSale.title"
    :pending="!order"
    :failed="failed"
    @retry="() => load(currentNo)"
  >
    <!-- 正文全靠 `order` 解引用，所以要一层 `v-if` 让 vue-tsc 收窄类型。
         **不写在 `<sh-scaffold>` 上**：写在那儿的话，`order` 为空时连外壳都不渲染 ——
         没有导航栏、没有一个字，退不回去。守卫留在这里，外壳照常在。 -->
    <template v-if="order">
      <!-- 已提交：展示进度 -->
      <template v-if="submitted">
        <view class="sh-card done">
          <text class="done__icon">✓</text>
          <text class="txt-title done__title">
            {{ asStatus === "REFUNDED" ? $t("afterSale.refunded") : $t("afterSale.applied") }}
          </text>
          <text class="txt-caption done__hint">{{ $t(doneHintKey) }}</text>
        </view>

        <view class="sh-card block">
          <view v-for="(n, i) in order.timeline.slice(-3)" :key="i" class="node sh-row">
            <view class="node__dot" :class="{ 'is-last': i === order.timeline.slice(-3).length - 1 }" />
            <text class="txt-caption node__label txt-ink">{{ n.label }}</text>
          </view>
        </view>

        <view class="sh-btn block" @tap="gotoOrder">{{ $t("pay.viewOrder") }}</view>
      </template>

      <!-- 申请表单 -->
      <template v-else>
        <view class="sh-card">
          <biz-sku-row
            v-for="(it, i) in order.items.filter((x) => !x.isGift)"
            :key="i"
            :cover="it.cover"
            :title="it.title"
            :spec="it.spec"
          >
            <template #right>
              <text class="txt-strong row__price sh-num">{{ money(it.price) }}</text>
            </template>
          </biz-sku-row>
        </view>

        <view class="sh-card block">
          <text class="txt-title">{{ $t("afterSale.pickType") }}</text>
          <view class="types">
            <sh-option
              v-for="tp in TYPES"
              :key="tp"
              class="type"
              :selected="type === tp"
              @tap="type = tp"
            >
              <text class="txt-strong type__t">{{ typeText(tp) }}</text>
              <text class="txt-caption type__d">{{ typeText(tp, "Desc") }}</text>
            </sh-option>
          </view>
        </view>

        <view class="sh-card block">
          <text class="txt-title">{{ $t("afterSale.pickReason") }}</text>
          <!-- 生鲜已发货/已签收：只收质量问题，说清楚为什么只剩这几项 -->
          <text v-if="fresh && (stage === 'SHIPPED' || stage === 'RECEIVED')"
            class="txt-caption sh-muted reasons__hint">{{ $t("afterSale.freshQualityOnly") }}</text>
          <view class="reasons sh-wrap">
            <view
              v-for="r in shownReasons"
              :key="r"
              class="sh-seg"
              :class="{ 'sh-seg--on': reason === r }"
              @tap="reason = r"
            >
              {{ $t(`afterSale.reason.${r}`) }}
            </view>
          </view>
        </view>

        <view class="sh-card block">
          <text class="txt-title">{{ $t("afterSale.detail") }}</text>
          <textarea
            v-model="detail"
            class="field__area ta"
            :placeholder="$t('afterSale.detailPh')"
            maxlength="200"
          />

          <!-- 已签收才传图：未收货看不到实物损坏，上传框不显示（AC1/AC2） -->
          <template v-if="showImageBox">
            <text class="sh-muted imglabel">{{ $t("afterSale.images") }}</text>
            <sh-uploader class="imgs" :list="images" :max="3" :width="160" :uploading="uploadingImg" @add="pickImages"></sh-uploader>
          </template>
        </view>

        <sh-actionbar :pad="180">
          <view class="sh-btn" :class="{ 'is-disabled': !canSubmit }" @tap="submit">
            {{ submitting ? $t("confirm.submitting") : $t("afterSale.submit") }}
          </view>
        </sh-actionbar>
      </template>
  
    </template>
  </sh-scaffold>
</template>

<style scoped>
.row__price {
  flex-shrink: 0;
}
.types {
  display: flex;
  gap: 16rpx;
  margin-top: 24rpx;
}
/* 描边 + 说明文字那一档由 sh-option 给，这里只管等分 */
.type {
  flex: 1;
}
.type__t {
  display: block;
}
.type__d {
  display: block;
  margin-top: 8rpx;
}
.reasons {
  gap: 16rpx;
  margin-top: 24rpx;
}
.reasons__hint {
  display: block;
  margin-top: 16rpx;
}
/* 盒子归 .field__area，这里只说「这一个框多高」—— 尺寸是版面，不是件的属性 */
.ta {
  min-height: 160rpx;
  margin-top: 20rpx;
}
.imglabel {
  display: block;
  margin-top: 28rpx;
}
/* 只留这一段与页面版面有关的外边距 —— 格子本身（尺寸 / 圆角 / 底色 / 「＋」）
   全在 `sh-uploader` 里。两页此前的 `.img` 一族**逐字节相同**：
   160rpx 方格、24rpx 圆角、faint 底、48rpx 的 `＋` 字符。
   顺带把那个 `＋` 换成真图标 —— 字符跟着字体走，三端字形不一样。 */
.imgs {
  margin-top: 16rpx;
}
.done {
  text-align: center;
  padding-top: 48rpx;
  padding-bottom: 40rpx;
}
.done__icon {
  display: block;
  width: 96rpx;
  height: 96rpx;
  line-height: 96rpx;
  margin: 0 auto;
  border-radius: 9999px;
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-size: 48rpx;
  font-weight: 700;
}
.done__title {
  display: block;
  margin-top: 24rpx;
}
.done__hint {
  display: block;
  margin-top: 16rpx;
}
.node {
  gap: 20rpx;
  padding: 12rpx 0;
}
.node__dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 9999px;
  background: var(--sh-line);
  flex-shrink: 0;
}
.node__dot.is-last {
  background: var(--sh-primary);
}
</style>
