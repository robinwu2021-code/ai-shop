<script setup lang="ts">
/**
 * 新建 / 编辑收货地址 —— **整页，不是弹层**。
 *
 * <p>此前这张表长在收货地址页的弹层里：7 个字段 + 3 条提示塞进一个高度受限的
 * 抽屉，用户看不到「还要填多少」。而下单页要用它时只能靠 `?new=1` 去把那个
 * 弹层打开 —— 于是「新建地址」有两个入口形态，两处的行为迟早会分叉。
 *
 * <p>这一页只做三件事：把要编辑的那条取回来、装上表单、存完回去。
 * 表单本身在 `biz-address-form` 里，只有一份。
 */
import { ref } from "vue";
import { onLoad, onShow } from "@dcloudio/uni-app";
import { api } from "@/api";
import type { Address } from "@shared/types";
import { pickedPlace } from "@/shared/address-pick";
import type { PlacePick } from "@/shared/address-pick";

/** 编辑模式要改的那一条。null = 新建 */
const address = ref<Address | null>(null);
/** 新建模式的预填（从选点页交回来的地点，或调用方带过来的坐标） */
const place = ref<PlacePick | null>(null);
/** 这是他的第一条地址吗 —— 决定要不要默认勾上「设为默认」 */
const first = ref(false);
/**
 * 取回来了没有。**编辑模式下必须等** —— 不等的话表单会先用空草稿初始化一次，
 * 而 `biz-address-form` 的草稿只在创建时读一次 props，
 * 于是用户看到的是一张空表，他会以为这条地址的内容丢了。
 */
const ready = ref(false);

function onSaved() {
  uni.navigateBack();
}

onLoad(async (q?: Record<string, string>) => {
  const list = await api.addressList().catch(() => [] as Address[]);
  first.value = !list.length;
  if (q?.addressId) {
    address.value = list.find((a) => a.addressId === q.addressId) ?? null;
  } else if (q?.latE6 && q?.lngE6) {
    /*
     * 带着坐标进来的（「把当前位置存成收货地址」那条路）。
     * **坐标是这条路的全部收获** —— 少了它，存下来的又是一条推不出聚落、
     * 判不了自送半径、导航打不开的地址，而界面上看不出区别。
     */
    place.value = {
      kind: "place",
      name: q.region ?? "",
      region: q.region ?? "",
      province: "", city: "", district: "",
      latE6: Number(q.latE6), lngE6: Number(q.lngE6),
    };
  }
  ready.value = true;
});

/**
 * 从选点页回来：**选中的地点要立刻落进表单**。
 *
 * <p>三种回法要分开：交回地点 → 预填；交回 manual → 保持现状（他自己打）；
 * 什么都没交回（点了系统返回）→ **什么都不做**。
 * 把第三种也当成「选了」的话，用户每次退出选点页都会被清一次表单。
 */
onShow(() => {
  const p = pickedPlace.take();
  if (p?.kind === "place") {
    place.value = p;
    address.value = null;
    // 换一次 key 让表单按新的预填重建 —— 草稿只在创建时读 props
    formKey.value += 1;
  }
});

const formKey = ref(0);
</script>

<template>
  <sh-scaffold :title-key="address ? 'address.edit' : 'address.add'">
    <biz-address-form
      v-if="ready"
      :key="formKey"
      :address="address"
      :place="place"
      :first="first"
      @saved="onSaved"
    ></biz-address-form>
  </sh-scaffold>
</template>
