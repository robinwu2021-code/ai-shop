<script setup lang="ts">
/*
 * 分享入口（TDD-C 端裂变与商家招募 §3.2；门店化 s08）。门户、商品页、资质页共用这一颗。
 *
 * **入口始终在**，两端行为不同：
 * · 小程序：一颗 `open-type="share"` 的原生按钮盖在上面，走微信转发；
 * · H5：复制带归因参数的链接（H5 没有胶囊菜单可以兜底）。
 *
 * 给了 `poster` 就改成**面板**：「发给朋友」/「生成海报」两块大选项，各说一句去哪儿。
 * 此前商品详情标题行并排「海报」「分享」两颗 —— 同一件事的两条路并排摆，人不知道点哪个。
 * 收进面板后朋友圈这条路一个像素都没少：海报仍是朋友圈唯一走得通的格式。
 *
 * 归因参数由调用方给（`inviterNo` / `merchantNo`），这里只负责拼进链接 ——
 * 少了它，分享带来的人算不到任何人头上。
 */
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { canNativeShare, withAttribution } from "@shared/ports/share";

const props = defineProps<{
  /** 分享落地的页面路径，如 `/pages/store/index?no=ST…&from=SHARE` */
  path: string;
  inviterNo?: string;
  merchantNo?: string;
  /** 紧凑模式：只画图标不画文字（商品页标题行那种并排的位置） */
  compact?: boolean;
  /** 面板里要不要「生成海报」一项。给了才弹面板；不给就是一颗直接分享的按钮 */
  poster?: boolean;
  /** 面板标题，如「分享这家店」 */
  sheetTitle?: string;
}>();
const emit = defineEmits<{ (e: "poster"): void }>();

const { t } = useI18n();
const native = canNativeShare();
const open = ref(false);

const link = computed(() =>
  withAttribution(props.path, {
    title: "",
    path: props.path,
    inviterNo: props.inviterNo,
    merchantNo: props.merchantNo,
  }));

/**
 * H5 复制。**复制的是带归因的那一份** —— 复制一个光秃秃的链接等于把归因丢了，
 * 而丢了之后没有任何症状：人照样进来，只是算不到分享他的那个人头上。
 */
function copy() {
  open.value = false;
  uni.setClipboardData({
    data: link.value,
    success: () => uni.showToast({ title: t("share.copied"), icon: "none" }),
  });
}

function onTap() {
  if (props.poster) {
    open.value = true;
    return;
  }
  if (!native) copy();
}

function toPoster() {
  open.value = false;
  emit("poster");
}
</script>

<template>
  <!-- 单一根节点：小程序里多根组件的宿主节点不稳。面板与按钮是兄弟 —— 放进按钮里的话，
       点面板（包括蒙层关闭）会冒泡到按钮上，关了又立刻打开 -->
  <view class="shareroot">
  <view class="shareact sh-center" :class="{ 'is-compact': compact }" @tap="onTap">
    <sh-icon name="share" :size="compact ? 32 : 28" color="var(--sh-ink)"></sh-icon>
    <text class="txt-caption sh-muted">{{ $t(poster || native ? "share.act" : "share.copy") }}</text>
    <!-- 没有面板时：小程序原生转发按钮盖在整块上，自己不占视觉 -->
    <button v-if="native && !poster" class="shareact__native" open-type="share"></button>
  </view>

  <sh-sheet v-if="poster" :visible="open" :title="sheetTitle || String($t('share.act'))" @close="open = false">
    <view class="opts sh-row">
      <view class="opt sh-fill sh-center" @tap="native ? undefined : copy()">
        <sh-icon name="share" :size="48" color="var(--sh-primary)"></sh-icon>
        <text class="txt-strong opt__t">{{ $t("share.toFriend") }}</text>
        <text class="txt-caption txt-quiet">{{ $t(native ? "share.toFriendSub" : "share.toFriendSubH5") }}</text>
        <!-- 小程序：转发必须由原生按钮触发，盖在这一块上 -->
        <button v-if="native" class="shareact__native" open-type="share" @tap="open = false"></button>
      </view>
      <view class="opt sh-fill sh-center" @tap="toPoster">
        <sh-icon name="grid" :size="48" color="var(--sh-primary)"></sh-icon>
        <text class="txt-strong opt__t">{{ $t("share.poster") }}</text>
        <text class="txt-caption txt-quiet">{{ $t("share.posterSub") }}</text>
      </view>
    </view>
  </sh-sheet>
  </view>
</template>

<style scoped>
/*
 * **宽度与方向由组件自己定**，不靠调用点给 class：小程序里调用点写的 class
 * 停在宿主节点上，组件根拿不到它 —— 于是同一份样式在 H5 对、在真机上散架。
 */
.shareact {
  position: relative;
  flex-shrink: 0;
  flex-direction: column;
  gap: 4rpx;
}
.shareroot {
  flex-shrink: 0;
}
.shareact.is-compact {
  gap: 2rpx;
  width: 72rpx;
}
.shareact__native {
  position: absolute;
  inset: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  border: none;
  opacity: 0;
}
.opts {
  gap: 24rpx;
  padding: 8rpx 0 24rpx;
}
/* 居中由 .sh-center 给，这里只把它竖过来 */
.opt {
  position: relative;
  flex-direction: column;
  gap: 8rpx;
  padding: 32rpx 16rpx;
  border-radius: 24rpx;
  background: var(--sh-faint);
}
.opt__t {
  margin-top: 8rpx;
}
</style>
