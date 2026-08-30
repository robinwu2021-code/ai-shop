<script setup lang="ts">
/**
 * 发布确认页（双版本，V279）：**先看差异，再决定发布**。
 *
 * <p><b>为什么独立成页</b>：差异清单是发布决定的依据，不是编辑的一部分 ——
 * 塞进编辑页的弹窗里，商家会在没看清「本次发布将改变什么」的时候就点了确认。
 * 差异由服务端 dry-run 烘焙后算（`mPublishPreview`）：商家没碰规格时
 * 「小罐 → 迷你罐」这类文案刷新只有后端算得出来，端上比原始 payload 永远看不见。
 *
 * <p>发布按钮在三种情况下点不动：没有差异（发布不改变任何东西）、
 * `blocked` 非空（引用了已停用的档，后端必拒 80017）、`stale`
 * （线上被别人改过，后端必拒 80018）—— 给一个必被拒的按钮，
 * 不如把「为什么不能发」写在按钮的位置上。
 */
import { computed, ref } from "vue";
import { onLoad } from "@dcloudio/uni-app";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import { useMerchantStore } from "@/stores/merchant";
import { ROUTES } from "@/shared/nav";
import type { PublishPreview } from "@/api/contract";
import { confirm } from "@ai-shop/ui/prompt";

const { t } = useI18n();
const merchant = useMerchantStore();

/** 草稿基版过期（后端 `GOODS_DRAFT_STALE`）。发布被它拒时就地刷新预览再核对 */
const GOODS_DRAFT_STALE = 80018;

const goodsNo = ref("");
const goodsTitle = ref("");
const preview = ref<PublishPreview | null>(null);
const loading = ref(true);
const publishing = ref(false);

/*
 * stale **不再禁用发布** —— 它换的是发布的语义：按钮变成「已核对差异，仍要发布」，
 * 调用带上 preview.baseVersion（这份差异就是以那一版线上为基准算的）。
 * 没有这条出路的话，商家自己下架再上架一次、生鲜每天改一次截单，
 * 草稿就永久发不出去了。真正禁用发布的只有两件事：没有差异、有被拦的档位。
 */
const canPublish = computed(() =>
  !!preview.value
  && preview.value.changes.length > 0
  && preview.value.blocked.length === 0);

async function load() {
  loading.value = true;
  try {
    // 标题只是页面上的锚（让人确认在发的是哪件货），与差异并行取
    const [p, g] = await Promise.all([
      api.mPublishPreview(goodsNo.value),
      api.mGoodsDetail(goodsNo.value),
    ]);
    preview.value = p;
    goodsTitle.value = g.title;
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    loading.value = false;
  }
}

onLoad((q) => {
  if (!q?.goodsNo) {
    uni.navigateBack();
    return;
  }
  goodsNo.value = q.goodsNo;
  void load();
});

async function publish() {
  if (!canPublish.value || publishing.value) return;
  const stale = !!preview.value?.stale;
  if (!(await confirm({
    title: t(stale ? "goods.publishStaleBtn" : "goods.publishBtn"),
    hint: goodsTitle.value,
  }))) return;
  publishing.value = true;
  try {
    // stale 时带上差异所基于的那一版 —— 确认之后线上又变了，后端照样拒（80018 → 下面刷新差异）
    const g = await api.mPublishGoods(
      goodsNo.value, stale ? preview.value!.baseVersion : undefined);
    /*
     * 审核开与关走到这儿是两种结局：关 = 已换版（草稿删行，hasDraft=false）；
     * 开 = 提交待审、线上照卖旧版（草稿还在，hasDraft=true）。
     * 用返回值区分，**不在端上再查一遍审核开关** —— 两处判断迟早漂移。
     */
    uni.showToast({
      title: t(g.hasDraft ? "goods.publishedPending" : "goods.published"),
      icon: "none",
    });
    setTimeout(() => uni.navigateBack(), 1200);
  } catch (e) {
    if ((e as { code?: number }).code === GOODS_DRAFT_STALE) {
      // 冲突不是终点：把最新差异摆出来（stale 会变 true，按钮随之说明原因）
      uni.showToast({ title: t("goods.publishConflict"), icon: "none" });
      void load();
      return;
    }
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    publishing.value = false;
  }
}

function toEdit() {
  uni.redirectTo({ url: `${ROUTES.goodsEdit}?goodsNo=${goodsNo.value}` });
}

/**
 * 放弃草稿 —— 冲突后的另一条出路（「以线上最新版为准重新编辑」），
 * 也是存了一版不想要的修改时的正常退路。草稿删除不可恢复，所以要过确认框。
 */
async function discard() {
  if (publishing.value) return;
  if (!(await confirm({
    title: t("goods.discardBtn"),
    hint: t("goods.discardHint"),
  }))) return;
  publishing.value = true;
  try {
    await api.mDiscardGoodsDraft(goodsNo.value);
    uni.showToast({ title: t("goods.discarded"), icon: "none" });
    setTimeout(() => uni.navigateBack(), 1000);
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: "none" });
  } finally {
    publishing.value = false;
  }
}
</script>

<template>
  <!-- 发布改的是线上商品 —— 与编辑同一道门（biz:goods），店员进不来 -->
  <sh-scaffold title-key="goods.publishTitle" :denied="!merchant.can('biz:goods')">
    <view v-if="loading" class="sh-card">
      <text class="txt-sub">{{ $t("common.loading") }}</text>
    </view>

    <template v-else-if="preview">
      <view class="sh-card sh-mb-sm">
        <text class="txt-strong">{{ goodsTitle }}</text>
      </view>

      <!-- 冲突横幅：线上被别人改过（运营处置/其他设备）。发布必被拒，先说清楚 -->
      <view v-if="preview.stale" class="banner banner--warn sh-mb-sm">
        <text class="txt-caption">{{ $t("goods.publishStale") }}</text>
      </view>

      <!-- 被拦的档位：与其点了发布再看 80017，不如进页面就点名 -->
      <view v-if="preview.blocked.length" class="banner banner--warn sh-mb-sm">
        <text class="txt-caption">{{ $t("goods.publishBlocked") }}</text>
        <text v-for="b in preview.blocked" :key="b" class="txt-caption blocked__item">· {{ b }}</text>
      </view>

      <view class="sh-card sh-mb-sm">
        <text class="txt-sub intro">
          {{ preview.changes.length ? $t("goods.publishIntro") : $t("goods.publishNoChange") }}
        </text>
        <!--
          一行一个字段：线上那份 → 发布后那份。before/after 是服务端渲染好的
          展示串（含烘焙后的规格文案），端上只排版不加工 —— 加工就是第二套烘焙。
        -->
        <view v-for="c in preview.changes" :key="c.field" class="diff">
          <text class="txt-caption txt-quiet">{{ c.label }}</text>
          <view class="diff__vals">
            <text class="txt-sub diff__before">{{ c.before || "—" }}</text>
            <!-- sh-icon 而不是「→」字符：字符伪图标跟着系统字形走，RTL 也不会自己翻；
                 chevronRight 在 DIRECTIONAL 名单里，阿语下自动镜像 -->
            <sh-icon name="chevronRight" :size="14" class="txt-quiet"></sh-icon>
            <text class="txt-sub diff__after">{{ c.after || "—" }}</text>
          </view>
        </view>
      </view>

      <view class="sh-row btns">
        <view class="sh-btn sh-btn--soft sh-fill" @tap="toEdit">
          {{ $t("goods.edit") }}
        </view>
        <!-- stale 时按钮就是确认动作本身：文案说清「你在对着此刻的线上版发布」 -->
        <view
          class="sh-btn sh-fill"
          :class="{ 'sh-btn--muted': !canPublish || publishing }"
          @tap="publish"
        >
          {{ $t(preview.stale ? "goods.publishStaleBtn" : "goods.publishBtn") }}
        </view>
      </view>
      <!-- 第三条路：放弃草稿、以线上为准。弱化成文字链 —— 它是退路，不是并列选项 -->
      <view class="discard-row">
        <text class="txt-caption discard-link" @tap="discard">{{ $t("goods.discardBtn") }}</text>
      </view>
    </template>
  </sh-scaffold>
</template>

<style scoped>
/* 块间距由外壳给（.sh-scaffold > * + *），顶层块不写纵向 margin */
.banner {
  padding: 16rpx 24rpx;
  border-radius: 16rpx;
  color: var(--sh-warning);
  background: var(--sh-warning-tint);
}
.blocked__item {
  display: block;
  margin-top: 8rpx;
}
.intro {
  display: block;
  margin-bottom: 16rpx;
}
.diff {
  padding: 14rpx 0;
  border-top: var(--sh-hairline-soft);
}
.diff__vals {
  display: flex;
  align-items: baseline;
  gap: 12rpx;
  /* 4rpx 网格上取 8 而不是 4：横向 gap 是 12rpx，纵向再紧到 4 会贴上 */
  margin-top: 8rpx;
}
/* 旧值划线弱化、新值常规 —— 眼睛先落在「将变成什么」上 */
.diff__before {
  text-decoration: line-through;
  color: var(--sh-sub);
}
.btns {
  gap: 20rpx;
}
.discard-row {
  text-align: center;
  padding: 8rpx 0;
}
/* 危险色但不是按钮：草稿删除不可恢复，值得一个警示色；但它是退路，不抢主操作的视觉位 */
.discard-link {
  color: var(--sh-danger);
}
</style>
