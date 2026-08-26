<script setup lang="ts">
// 外观面板：配色(4) × 明暗(3) × 语言(中/英/阿)。选中即时全局生效，实时预览，无需重载。
import { useThemeStore } from "../stores/theme";
import { useAppStore } from "../stores/app";
import { useMarketStore } from "../stores/market";
import { useShell } from "../shell";
import { MODES, SKIN_HEX, skinsOf, type SkinId } from "@shared/design/tokens";

/** 两组配色：纯白底组只换主色与字色，整套组连背景一起换 */
const PURE_SKINS = skinsOf("pure");
const FULL_SKINS = skinsOf("full");

/** 色块用**浅色档主色**做预览 —— 深色档是给深色模式用的，放在选择器里会认不出 */
function skinColor(id: SkinId): string {
  return SKIN_HEX[id].light;
}
import { LANGS, MARKETS } from "@shared/utils/constants";
import type { Lang, MarketId } from "@shared/types";

defineProps<{ visible: boolean }>();
const emit = defineEmits<{ (e: "update:visible", v: boolean): void }>();

const theme = useThemeStore();
const app = useAppStore();
const market = useMarketStore();
const shell = useShell();

/**
 * 切语言/切市场之后要重拉什么，**由 app 决定**：
 * C 端有社区文案与购物车要按新语言/新货币重取（否则中英阿混排、价格是上一个市场的），
 * B 端的服务端文案在各页 onShow 时自然重取，不需要额外动作。
 * 这个差异写进 shell 配置，组件本身不认识任何业务 store。
 */
async function switchLang(lang: Lang) {
  app.setLang(lang);
  await shell.onLangChange?.();
}

async function switchMarket(id: MarketId) {
  market.setMarket(id);
  await shell.onMarketChange?.();
}

function close() {
  emit("update:visible", false);
}
</script>

<template>
  <view v-if="visible" class="sheet">
    <view class="sheet__mask" @tap="close" />
    <view class="sheet__panel" :class="app.dirClass">
      <view class="sheet__grip" />
      <text class="txt-title">{{ $t("theme.title") }}</text>

      <!-- 两组配色分开：纯白底组只换主色与字色，整套组连背景一起换。
           **色点上不写字** —— 颜色本身就是最清楚的标识，压上文字反而看不清色；
           选中是谁、什么用途，都交给下方那一行 tip。 -->
      <text class="sheet__label">{{ $t("theme.skinPure") }}</text>
      <view class="swatches">
        <view
          v-for="id in PURE_SKINS"
          :key="id"
          class="swatch"
          :class="{ 'is-on': theme.skin === id }"
          :style="{ background: skinColor(id) }"
          @tap="theme.setSkin(id)"
        >
          <text v-if="theme.skin === id" class="swatch__tick">✓</text>
        </view>
      </view>

      <text class="sheet__label">{{ $t("theme.skinFull") }}</text>
      <view class="swatches">
        <view
          v-for="id in FULL_SKINS"
          :key="id"
          class="swatch"
          :class="{ 'is-on': theme.skin === id }"
          :style="{ background: skinColor(id) }"
          @tap="theme.setSkin(id)"
        >
          <text v-if="theme.skin === id" class="swatch__tick">✓</text>
        </view>
      </view>

      <!-- 选中的是哪套、什么用途，只在这里说一次 -->
      <text class="sheet__tip">
        {{ $t(`skin.${theme.skin}`) }} · {{ $t(`skin.${theme.skin}Desc`) }}
      </text>

      <text class="sheet__label">{{ $t("theme.mode") }}</text>
      <view class="opts">
        <view
          v-for="m in MODES"
          :key="m"
          class="opts__item"
          :class="{ 'is-on': theme.mode === m }"
          @tap="theme.setMode(m)"
        >
          {{ $t(`mode.${m}`) }}
        </view>
      </view>

      <text class="sheet__label">{{ $t("theme.language") }}</text>
      <view class="opts">
        <view
          v-for="l in LANGS"
          :key="l.id"
          class="opts__item"
          :class="{ 'is-on': app.lang === l.id }"
          @tap="switchLang(l.id)"
        >
          {{ l.label }}
        </view>
      </view>

      <text class="sheet__label">{{ $t("market.label") }}</text>
      <view class="opts opts--stack">
        <view
          v-for="m in MARKETS"
          :key="m.id"
          class="opts__item"
          :class="{ 'is-on': market.market === m.id }"
          @tap="switchMarket(m.id)"
        >
          {{ $t(m.labelKey) }}
        </view>
      </view>

      <view class="sh-btn sheet__done" @tap="close">{{ $t("common.done") }}</view>
    </view>
  </view>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: 100;
}
.sheet__mask {
  position: absolute;
  inset: 0;
  background: var(--sh-scrim);
}
.sheet__panel {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--sh-surface);
  border-radius: 44rpx 44rpx 0 0;
  padding: 24rpx 36rpx calc(48rpx + env(safe-area-inset-bottom));
  /* 内容比屏幕高时必须能滚，否则超出的部分被顶到视口外、够不着。
     皮肤从 4 套加到 8 套时就撞上了这个：面板从「明暗」开始显示，
     上面的「配色」整段不见了 —— 而它恰恰是这个面板的第一功能。
     bottom:0 的弹层没有 max-height 就是这个后果，加内容前先给约束。 */
  max-height: 85vh;
  box-sizing: border-box;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
.sheet__grip {
  width: 72rpx;
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  margin: 0 auto 32rpx;
}
.sheet__label {
  display: block;
  margin: 44rpx 0 20rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
}
/* 一行固定 4 个、超出换行。
   原来是 flex:1 平分一行 —— 4 套皮肤时每格够宽，加到 8 套后每格只剩 1/8，
   「生鲜绿」被压成竖排三行。**格子宽度不能由数量决定**，否则加一套就重排一次。 */
.swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
/* 色块本身就是选项：不写名字、不写描述。
   一是文字压在色块上会跟着皮肤色变，浅色皮肤上几乎看不清；
   二是 8 个格子各挂两行字会把面板撑到要滚两屏。
   选中项的名称与用途由下方 tip 承担，一次只说一个。 */
.swatch {
  width: calc((100% - 48rpx) / 4);
  height: 96rpx;
  box-sizing: border-box;
  border-radius: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
}
.swatch.is-on {
  /* 选中靠一圈描边 + 勾，不靠底色 —— 底色已经被皮肤色占了 */
  box-shadow: 0 0 0 6rpx var(--sh-bg), 0 0 0 12rpx var(--sh-ink);
}
.swatch__tick {
  color: #fff;
  font-size: 34rpx;
  font-weight: 600;
  /* 勾压在任意皮肤色上都要看得见，用遮罩 token 做一层暗描边兜底（不写死颜色） */
  text-shadow: 0 2rpx 6rpx var(--sh-scrim);
}
.sheet__tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: var(--sh-sub);
  line-height: 1.5;
}
.opts {
  display: flex;
  gap: 16rpx;
}
/* 地区文案长，横排会挤 —— 竖排一行一个 */
.opts--stack {
  flex-direction: column;
}
.opts__item {
  flex: 1;
  text-align: center;
  padding: 22rpx 0;
  border-radius: 24rpx;
  background: var(--sh-faint);
  color: var(--sh-sub);
  font-size: 26rpx;
}
.opts__item.is-on {
  background: var(--sh-primary);
  color: var(--sh-on-primary);
  font-weight: 600;
}
.sheet__done {
  margin-top: 52rpx;
}
</style>
