<script setup lang="ts">
// 带箭头的行内链接：「去填地址 ›」「切换门店 ›」「查看详情 ›」这一类。
//
// **收编的是什么**：B 端有 9 个调用点，各自写了一个 24rpx + `--sh-primary-text`
// 的类（`.applylink` / `.scope__switch` / `.msg__more` / `.tag__switch` / `.sum__go`），
// 而 `.sh-link` 就是这两条声明 —— 五个页面各自重新声明了一遍公共类。
//
// **箭头此前是词条里的一个 `›` 字符**，藏在 i18n 里：
// `goApplyWithLicense: "有营业执照？直接走入驻 ›"`。这有三个后果：
//   · 判据扫不到 —— 「文字当箭头」那条只看模板，词条里的看不见（10 条就这么漏了）
//   · 翻译要跟着抄标点 —— 三种语言各留一份，漏一个就长得不一样
//   · 它跟着字体走，拿不到 sh-icon 的尺寸与颜色档
//
// **但那个字符有一件事做对了**：`›`（U+203A）是 Unicode 的 bidi-mirrored 字符，
// 阿语下浏览器自己把它翻成 `‹`。换成图标就不翻了 —— 所以镜像补在了
// `sh-icon` 的 DIRECTIONAL 名单 + base.css 的 `.sh-root.is-rtl .icon--dir` 上。
// **不补的话这次收编会把阿语弄反**，而且没人会报。
withDefaults(
  defineProps<{
    /** 链接文字。也可以走默认插槽（要拼变量时） */
    text?: string;
    /** 次要形态：灰字灰箭头。用在「这一行本身不是主要动作」的地方 */
    quiet?: boolean;
  }>(),
  { text: "", quiet: false },
);
</script>

<template>
  <view class="go" :class="{ 'go--quiet': quiet }">
    <text class="go__t"><slot>{{ text }}</slot></text>
    <sh-icon
      name="chevronRight"
      :size="20"
      :color="quiet ? 'var(--sh-sub)' : 'var(--sh-primary-text)'"
    ></sh-icon>
  </view>
</template>

<style scoped>
/* inline-flex 而不是 flex：它要能跟在一行文字后面，也要能单独占一行
   （调用点给 `display:block` 的外层或直接放进 flex 容器） */
.go {
  display: inline-flex;
  align-items: center;
  gap: 4rpx;
}
/* 与 `.sh-link` 同值 —— 那是这一族的字号与颜色档 */
.go__t {
  font-size: 24rpx;
  color: var(--sh-primary-text);
}
.go--quiet .go__t {
  color: var(--sh-sub);
}
</style>
