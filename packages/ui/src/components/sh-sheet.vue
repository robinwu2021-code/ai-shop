<script setup lang="ts">
/**
 * 通用底部弹层。
 *
 * <p><b>为什么不用 `uni.showModal`</b>：那是系统弹框，标题与输入框不是同一套字，
 * 字号、行高、对齐都不归我们管 —— 看起来就是「粗糙、字不齐」，而这一点改不了。
 * 而且它 `editable=true` 时把 `content` 当**预填值**（不是说明文字），
 * 于是能放的说明只剩一个 placeholder。
 *
 * <p><b>为什么不做页内展开</b>：展开会把下面的内容整段顶走，商家一边填一边
 * 失去上下文；而弹层把注意力收在一件事上，做完就回到原地。
 *
 * <p><b>必须有 `max-height`</b>（照抄 sh-theme-sheet 踩过的坑）：
 * bottom:0 的弹层内容一多就把上半截顶到视口外，够不着 ——
 * 而候选规格是会随运营配置增长的，今天 5 条不代表明天不是 25 条。
 */
defineProps<{
  visible: boolean;
  title: string;
  /** 副标题：一句话说清这个弹层里的东西是什么、代价在哪 */
  hint?: string;
  /**
   * 叠在**另一个弹层之上**（省市区选择器开在地址表单弹层上就是这个形态）。
   *
   * 不加这一档的话两层同 z-index，谁在上面取决于 DOM 顺序 ——
   * 在 H5 上碰巧是对的，而一旦某一层落进了别的层叠上下文（transform、
   * position:sticky 的祖先都会造一个），顺序就翻过来：
   * 弹层开了、蒙层也在，但内容被压在下面，点哪儿都没反应，且**不报错**。
   */
  stacked?: boolean;
  /**
   * 内容**通铺到边**：一行行的列表用这个，行的分隔线要压到弹层两侧，
   * 中间留一段白边的话，那条线看着像断了。
   *
   * 只影响正文那一段 —— 标题、副标题、页脚仍按面板的留白走。
   */
  flush?: boolean;
}>();

const emit = defineEmits<{ close: [] }>();
</script>

<template>
  <view v-if="visible" class="sheet" :class="{ 'sheet--stacked': stacked }">
    <view class="sheet__mask" @tap="emit('close')" />
    <view class="sheet__panel" :class="{ 'sheet__panel--tall': !!$slots.foot }">
      <view class="sheet__grip" />
      <view class="sheet__head">
        <text class="txt-title sheet__title">{{ title }}</text>
        <!-- 标题右侧的附加内容（「已选 3 · 展开」这一类）。
             它必须**始终可见** —— 放进正文的话列表一滚就没了，而「我选了几条」
             是这一屏从头到尾都要能看见的东西。 -->
        <slot name="head"></slot>
        <sh-icon-btn name="close" :size="28" :box="48" @tap="emit('close')"></sh-icon-btn>
      </view>
      <text v-if="hint" class="txt-caption sheet__hint">{{ hint }}</text>
      <!-- 不跟着滚的工具条：分栏、搜索框、已选清单。
           跟着滚的话，想换个分栏得先滚回顶部。 -->
      <view v-if="$slots.toolbar" class="sheet__toolbar"><slot name="toolbar"></slot></view>
      <view class="sheet__body" :class="{ 'sheet__body--flush': flush }"><slot /></view>
      <!--
        贴底页脚（「确定（3）」这一类）。**有它时面板就不再整体滚**：
        面板定高、中间那段滚、页脚钉住 —— 否则列表一长，确定按钮就滚出视野，
        而那正是这一屏唯一的出口。
        两个 picker 各自实现过一遍这个形状（84vh + scroll-view + .foot），
        所以它不是某一页的特殊需求，是这个件缺的一半。
      -->
      <view v-if="$slots.foot" class="sheet__foot"><slot name="foot" /></view>
    </view>
  </view>
</template>

<style scoped>
.sheet {
  position: fixed;
  inset: 0;
  z-index: var(--sh-z-sheet);
}

/* 比 sh-dialog（200）低一档：对话框永远该在最上面，它是要人立刻回答的那一个 */
.sheet--stacked {
  z-index: var(--sh-z-sheet-stacked);
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
  /* 横向留白走变量，好让「通铺到边」的那两处用负外边距精确抵消它 ——
     抄一个 36 过去的话，改一处另一处就错位，而错位只有几像素，没人会去量 */
  --sheet-pad-x: 36rpx;
  padding: 24rpx var(--sheet-pad-x) 48rpx;
  padding: 24rpx var(--sheet-pad-x) calc(48rpx + env(safe-area-inset-bottom, 0px));
  /* 见类注释：没有 max-height 的 bottom:0 弹层，内容一多就把上半截顶出视口 */
  max-height: 78vh;
  box-sizing: border-box;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/*
 * 有页脚时换一种滚法：**面板定高、中间那段滚、页脚钉住**。
 * 84vh 不是新数，是两个 picker 各自写过的那个 —— 它们要的就是「几乎占满屏、
 * 但露出一点上文，让人知道底下还有页面」。
 */
.sheet__panel--tall {
  display: flex;
  flex-direction: column;
  height: 84vh;
  max-height: 84vh;
  overflow: hidden;
  /* 安全区改由页脚自己给：面板不滚了，底部留白留在这儿会变成一段够不着的空白 */
  padding-bottom: 0;
}
.sheet__panel--tall .sheet__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
/* 通铺到边：把面板的横向留白抵消掉。两处都从同一个变量算，不各写一个数 */
.sheet__body--flush,
.sheet__foot {
  margin-inline: calc(var(--sheet-pad-x) * -1);
}
/* 工具条与正文同宽（flush 时一起通铺）—— 搜索框的左右边界要与列表行对齐 */
.sheet__toolbar {
  flex-shrink: 0;
}
.sheet__panel--tall .sheet__toolbar {
  /* 面板定高时它不参与滚动，也不许被挤扁 */
  flex-shrink: 0;
}
.sheet__foot {
  flex-shrink: 0;
  padding: 16rpx 24rpx;
  /* 兜底那一行不能省：不认 env() 的内核上整条声明被丢弃，页脚会贴到屏幕最底下，
     被 iPhone 的横条压住一截。上面那条 `padding` 已经给了 16rpx，
     这里再显式写一遍同名声明，让下面那条 calc 有东西可退回 */
  padding-bottom: 16rpx;
  padding-bottom: calc(16rpx + env(safe-area-inset-bottom, 0px));
  border-top: var(--sh-hairline);
}

.sheet__grip {
  width: 72rpx;
  height: 8rpx;
  border-radius: 9999px;
  background: var(--sh-faint);
  margin: 0 auto 28rpx;
}

.sheet__head {
  display: flex;
  align-items: center;
  gap: 16rpx;
}

/* 34rpx/600 = 字阶的 .txt-title。此前是 32rpx/700 —— 32 不在七档上，
   而 700 按字阶只给价格。**组件库带头破的那一档，页面照抄不奇怪**：
   goods-list 的 .sheet__t 与 order 的 .dlg__title 也都是 32rpx。 */
.sheet__title {
  flex: 1;
  color: var(--sh-ink);
}


.sheet__hint {
  display: block;
  margin-top: 8rpx;
  color: var(--sh-sub);
}
</style>
