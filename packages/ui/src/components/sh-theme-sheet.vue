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
/*
 * 关闭走 `close`，不走 `update:visible`。
 *
 * 全仓两套写法并存过（`close` 六个件、`update:visible` 三个）—— 2026-09-06 统一。
 * 取 `close` 的理由不是「它多数」，是**可见性归调用点管**：`update:visible` 让件
 * 自己把 `visible` 改掉，于是「关之前先问一句」这种事没有插手的地方；
 * `close` 只是报告「用户想关」，关不关由页面决定。底座 `sh-sheet` 本来就是这个口径，
 * 包着它的件却往外翻译成另一个事件，读的人要在两层之间换一次脑筋。
 */
const emit = defineEmits<{ close: [] }>();

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
  emit("close");
}
</script>

<template>
  <view v-if="visible" class="sheet">
    <view class="sh-mask" @tap="close" />
    <view class="sh-panel sheet__panel" :class="app.dirClass">
      <view class="sh-grip" />
      <text class="txt-title">{{ $t("theme.title") }}</text>

      <!-- 两组配色分开：纯白底组只换主色与字色，整套组连背景一起换。
           **色点上不写字** —— 颜色本身就是最清楚的标识，压上文字反而看不清色；
           选中是谁、什么用途，都交给下方那一行 tip。 -->
      <text class="txt-caption sheet__label">{{ $t("theme.skinPure") }}</text>
      <view class="swatches">
        <view
          v-for="id in PURE_SKINS"
          :key="id"
          class="sh-center swatch"
          :class="{ 'is-on': theme.skin === id }"
          :style="{ background: skinColor(id) }"
          @tap="theme.setSkin(id)"
        >
          <sh-icon v-if="theme.skin === id" class="swatch__tick" name="check" :size="28" color="#fff"></sh-icon>
        </view>
      </view>

      <text class="txt-caption sheet__label">{{ $t("theme.skinFull") }}</text>
      <view class="swatches">
        <view
          v-for="id in FULL_SKINS"
          :key="id"
          class="sh-center swatch"
          :class="{ 'is-on': theme.skin === id }"
          :style="{ background: skinColor(id) }"
          @tap="theme.setSkin(id)"
        >
          <sh-icon v-if="theme.skin === id" class="swatch__tick" name="check" :size="28" color="#fff"></sh-icon>
        </view>
      </view>

      <!-- 选中的是哪套、什么用途，只在这里说一次 -->
      <text class="txt-caption sheet__tip">
        {{ $t(`skin.${theme.skin}`) }} · {{ $t(`skin.${theme.skin}Desc`) }}
      </text>

      <text class="txt-caption sheet__label">{{ $t("theme.mode") }}</text>
      <view class="opts">
        <view
          v-for="m in MODES"
          :key="m"
          class="txt-sub opts__item"
          :class="{ 'is-on': theme.mode === m }"
          @tap="theme.setMode(m)"
        >
          {{ $t(`mode.${m}`) }}
        </view>
      </view>

      <text class="txt-caption sheet__label">{{ $t("theme.language") }}</text>
      <view class="opts">
        <view
          v-for="l in LANGS"
          :key="l.id"
          class="txt-sub opts__item"
          :class="{ 'is-on': app.lang === l.id }"
          @tap="switchLang(l.id)"
        >
          {{ l.label }}
        </view>
      </view>

      <text class="txt-caption sheet__label">{{ $t("market.label") }}</text>
      <view class="opts opts--stack">
        <view
          v-for="m in MARKETS"
          :key="m.id"
          class="txt-sub opts__item"
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
  z-index: var(--sh-z-sheet);
}
/* 几何全部走 `.sh-panel`（base.css），这里只补这一个面板独有的约束。
   ⚠️ 抓手条此前是 `margin-bottom: 32rpx`，另外两个弹层是 28 —— 同一道横条三个数，
   收编时归 28（`.sh-grip`）。 */
.sheet__panel {
  /* 内容比屏幕高时必须能滚，否则超出的部分被顶到视口外、够不着。
     皮肤从 4 套加到 8 套时就撞上了这个：面板从「明暗」开始显示，
     上面的「配色」整段不见了 —— 而它恰恰是这个面板的第一功能。
     bottom:0 的弹层没有 max-height 就是这个后果，加内容前先给约束。
     85 而不是 sh-sheet 的 78：这一屏是三组选择器，矮了就要滚两下。 */
  max-height: 85vh;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
.sheet__label {
  display: block;
  margin: 44rpx 0 20rpx;
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
}
.swatch.is-on {
  /* 选中靠一圈描边 + 勾，不靠底色 —— 底色已经被皮肤色占了 */
  box-shadow: 0 0 0 6rpx var(--sh-bg), 0 0 0 12rpx var(--sh-ink);
}
/* 勾压在任意皮肤色上都要看得见，用遮罩 token 做一层暗投影兜底（不写死颜色）。
   `drop-shadow` 而不是 `text-shadow`：这枚勾现在是 sh-icon（mask 出来的形状），
   text-shadow 对它无效 —— 换图标时最容易连带丢掉的就是这一条 */
/*
 * 白色对勾压在**任意皮肤色**的色块上，投影是给它做可读性衬底的 ——
 * 不是高度投影，所以不走 `--sh-shadow-*`（那两档是 8% / 16% 的纵深，
 * 这里要的是「无论底下什么颜色都读得出白」，浓一档才对）。
 *
 * ⚠️ **也不借 `--sh-scrim`**（此前就是）：那是蒙层色，改一次弹层浓度
 * 会连带把这个对勾的衬底改掉，而两者毫无关系。全仓只此一处，直接写值。
 */
.swatch__tick {
  filter: drop-shadow(0 2rpx 6rpx rgba(10, 12, 16, 0.45));
}
.sheet__tip {
  display: block;
  margin-top: 16rpx;
  color: var(--sh-sub);
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
}
.opts__item.is-on {
  background: var(--sh-primary);
  color: var(--sh-on-primary);
}
.sheet__done {
  margin-top: 52rpx;
}
</style>
