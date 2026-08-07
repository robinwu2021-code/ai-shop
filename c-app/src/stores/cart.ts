// 购物车：服务端为准，本地做乐观更新。按履约方式分组结算。
import { defineStore } from "pinia";
import { api } from "@/api";
import { STORAGE } from "@shared/utils/constants";
import type { CartItem, FulfillmentType } from "@shared/types";

export const useCartStore = defineStore("cart", {
  state: () => ({
    items: [] as CartItem[],
    loading: false,
  }),

  getters: {
    count: (s) => s.items.reduce((n, it) => n + it.qty, 0),
    validItems: (s) => s.items.filter((it) => !it.invalidReason),
    totalFen(): number {
      return this.validItems.reduce((n, it) => n + it.price * it.qty, 0);
    },
    /** 按履约方式分组 —— 结算时一组一单 */
    groups(): { fulfillment: FulfillmentType; items: CartItem[] }[] {
      const map = new Map<FulfillmentType, CartItem[]>();
      for (const it of this.validItems) {
        const arr = map.get(it.fulfillment) ?? [];
        arr.push(it);
        map.set(it.fulfillment, arr);
      }
      return [...map.entries()].map(([fulfillment, items]) => ({ fulfillment, items }));
    },
  },

  actions: {
    async load() {
      this.loading = true;
      try {
        this.items = await api.cartList();
      } finally {
        this.loading = false;
      }
    },
    async add(goodsNo: string, skuNo: string, qty = 1) {
      this.items = await api.cartAdd(goodsNo, skuNo, qty);
    },
    async update(skuNo: string, qty: number) {
      this.items = await api.cartUpdate(skuNo, qty);
    },
    async remove(skuNos: string[]) {
      this.items = await api.cartRemove(skuNos);
    },
    /** 切换社区后：移除在新社区不可售的商品（由后端标 invalidReason） */
    async refreshOnCommunityChange() {
      await this.load();
    },
  },

  persist: {
    key: STORAGE.cart,
    pick: ["items"],
  },
});
