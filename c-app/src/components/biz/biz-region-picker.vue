<script setup lang="ts">
/**
 * 省 / 市 / 区三级选择器。
 *
 * **为什么不复用「有已开通社区的区域」那个接口**：它答的是「我能在哪儿取货」，
 * 只列有社区的区。拿它填地址，没开通的区整片消失 —— 人住在那儿，却选不出来。
 * 这里走 `/mp/regions`，全量区划，且**止于区县**（地址表只有三列，街道没处放）。
 *
 * **为什么要有它**：手填的一串字进不了 `province/city/district` 三列，
 * 于是按省算运费、按区派单全在 null 上求值，一条都不命中，而页面看起来完全正常。
 * 手填那条路仍然留着（存量地址、拆不动的写法），但默认路径是点出来的。
 */
import { ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "@/api";
import type { RegionNode } from "@shared/types";

const props = defineProps<{
  visible: boolean;
  /**
   * 当前已填的省市区，**只用来显示「正在替换的是什么」**。
   *
   * 不拿它回填面包屑：地址簿存的是名字，没有区划码，
   * 从名字反查不出该加载哪一级的子节点。硬回填出来的面包屑点不动 ——
   * 看起来选好了，实际上一级都走不通，比空着更糟。
   */
  current?: string | null;
}>();

const emit = defineEmits<{
  close: [];
  pick: [value: { province: string; city: string; district: string }];
}>();

const { t } = useI18n();

/** 已选到第几级：0=正在选省，1=正在选市，2=正在选区 */
const step = ref(0);
const chosen = ref<{ name: string; code: string }[]>([]);
const rows = ref<RegionNode[]>([]);
const loading = ref(false);
/**
 * 加载失败要**看得见**。此前多处是 `.catch(() => [])` —— 网断了和
 * 「这一级本来就是空的」长得一模一样，人只会以为没数据，然后去手填。
 */
const failed = ref(false);

async function load(parent?: string) {
  loading.value = true;
  failed.value = false;
  try {
    rows.value = await api.regions(parent);
  } catch {
    rows.value = [];
    failed.value = true;
  } finally {
    loading.value = false;
  }
}

function reset() {
  step.value = 0;
  chosen.value = [];
  void load();
}

watch(
  () => props.visible,
  (on) => {
    if (on) reset();
  },
  { immediate: true },
);

async function choose(r: RegionNode) {
  chosen.value = [...chosen.value.slice(0, step.value), { name: r.name, code: r.regionCode }];
  /*
   * **不设区的地级市**（东莞、中山…）在区划表里只有市这一级。
   * 硬要人再点一级，他会一直点不出下一屏 —— 所以 `hasChild=false` 就收工，
   * 区县留空，由 `isCompleteRegion` 在表单那头提示。
   */
  if (!r.hasChild || chosen.value.length >= 3) {
    emit("pick", {
      province: chosen.value[0]?.name ?? "",
      city: chosen.value[1]?.name ?? "",
      district: chosen.value[2]?.name ?? "",
    });
    return;
  }
  step.value = chosen.value.length;
  await load(r.regionCode);
}

/**
 * 点面包屑回到某一级重选。
 *
 * **不清空 `chosen`** —— 清了的话回退一级，已经选过的那两级就从面包屑上消失，
 * 人会以为自己把之前的选择弄丢了。真正的截断发生在 `choose()` 里：
 * 只有**改选了**才丢掉它下面的层级。
 */
async function back(i: number) {
  if (i >= chosen.value.length) return;
  step.value = i;
  await load(i === 0 ? undefined : chosen.value[i - 1]!.code);
}

const LEVELS = ["province", "city", "district"] as const;
function crumbs() {
  return LEVELS.map((k, i) => ({
    key: String(i),
    label: chosen.value[i]?.name ?? (i === step.value ? String(t("address.regionSelect")) : String(t(`address.${k}`))),
  })).slice(0, Math.max(step.value + 1, chosen.value.length + 1));
}
</script>

<template>
  <sh-sheet
    stacked
    :visible="visible"
    :title="String($t('address.regionTitle'))"
    :hint="current ? String(current) : String($t('address.regionHint'))"
    @close="emit('close')"
  >
    <sh-tabs :items="crumbs()" :active="String(step)" @change="back(Number($event))"></sh-tabs>

    <sh-empty v-if="failed" bare :text="String($t('address.regionFailed'))"></sh-empty>
    <text v-else-if="loading" class="lv__ph sh-ph">{{ $t("common.loading") }}</text>
    <sh-empty v-else-if="!rows.length" bare :text="String($t('address.regionEmpty'))"></sh-empty>

    <view v-else class="lv">
      <text
        v-for="r in rows"
        :key="r.regionCode"
        class="lv__node"
        :class="{ 'is-on': chosen[step]?.code === r.regionCode }"
        @tap="choose(r)"
      >
        {{ r.name }}
      </text>
    </view>
  </sh-sheet>
</template>

<style scoped>
.lv {
  max-height: 52vh;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* 一行一个，通铺到边 —— 分隔靠发丝线而不是间距，一屏能多放三四个 */
.lv__node {
  display: block;
  padding: 26rpx 4rpx;
  font-size: 28rpx;
  color: var(--sh-ink);
  border-bottom: 2rpx solid var(--sh-hairline-soft);
}

.lv__node.is-on {
  color: var(--sh-primary-text);
  font-weight: 600;
}

.lv__ph {
  display: block;
  padding: 48rpx 0;
  text-align: center;
  font-size: 26rpx;
}
</style>
