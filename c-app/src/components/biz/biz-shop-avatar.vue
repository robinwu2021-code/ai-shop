<script setup lang="ts">
// 店铺头像。**此前六处都把 logo 当文字打印**（`{{ logo || 🏪 }}`）——
// 今天没人传过图片 logo 才没暴露；一旦有人传了，那六处会把一条 https 地址按大字号铺出去，
// 与商品封面当年那次（sh-cover 的由来）是同一个坑。
//
// 三档，按顺序取第一个有的：
//   ① logo 是图片地址 → 用它
//   ② 自营店 → 虹选品牌面（brand/build.py 生成，与 C 端 App 图标同一套参数，勿手改）
//   ③ 店名首字 —— 不再用 🏪 这类占位表情：一排店全是同一个表情，等于没有头像
//
// 尺寸走 `size` 而不是调用点的 class：小程序里调用点的 class 落在宿主节点上、
// 进不到组件内部（见 sh-cover 那段注释），用参数传进来两端一致。
import { computed } from "vue";

/** 自营店头像。只有 C 端小程序包里有这张图（c-app/src/static/brand/） */
const SELF_STORE_AVATAR = "/static/brand/store-self.png";

const props = withDefaults(
  defineProps<{ name?: string; logo?: string; selfOperated?: boolean; size?: number }>(),
  { name: "", logo: "", selfOperated: false, size: 88 },
);

const isImg = (s: string) => /^(https?:)?\/\//.test(s) || s.startsWith("/") || s.startsWith("data:");

const src = computed(() => {
  if (props.logo && isImg(props.logo)) return props.logo;
  return props.selfOperated ? SELF_STORE_AVATAR : "";
});

/** 店名首字。英文店名取首字母大写 */
const initial = computed(() => (props.name.trim()[0] ?? "").toUpperCase());

const boxStyle = computed(() => ({
  width: `${props.size}rpx`,
  height: `${props.size}rpx`,
  borderRadius: `${Math.round(props.size * 0.27)}rpx`,
}));
const textStyle = computed(() => ({ fontSize: `${Math.round(props.size * 0.44)}rpx` }));
</script>

<template>
  <view class="sh-center av" :style="boxStyle">
    <image v-if="src" :src="src" mode="aspectFill" class="av__img" />
    <text v-else class="txt-bold av__txt" :style="textStyle">{{ initial }}</text>
  </view>
</template>

<style scoped>
.av {
  flex-shrink: 0;
  overflow: hidden;
  background: var(--sh-primary-tint);
}
.av__img {
  width: 100%;
  height: 100%;
}
.av__txt {
  color: var(--sh-primary-text);
}
</style>
