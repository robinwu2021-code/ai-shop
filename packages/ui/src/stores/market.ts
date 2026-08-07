// 市场（地区）store：一个市场决定「货币 + 时区」，语言仍可独立切换
// （中东用户也可能用英文，中国用户也可能看英文界面）。
import { defineStore } from "pinia";
import { DEFAULT_MARKET, MARKETS, STORAGE } from "@shared/utils/constants";
import { setCurrentCurrency } from "@shared/utils/money";
import { setCurrentOffset } from "@shared/utils/datetime";
import type { CurrencyCode, MarketId } from "@shared/types";

function defOf(id: MarketId) {
  return MARKETS.find((m) => m.id === id) ?? MARKETS[0];
}

export const useMarketStore = defineStore("market", {
  state: () => ({
    market: DEFAULT_MARKET as MarketId,
  }),

  getters: {
    currency(): CurrencyCode {
      return defOf(this.market).currency as CurrencyCode;
    },
    utcOffsetMinutes(): number {
      return defOf(this.market).utcOffsetMinutes;
    },
  },

  actions: {
    init() {
      this.market = (uni.getStorageSync(STORAGE.market) as MarketId) || DEFAULT_MARKET;
      this.apply();
    },
    setMarket(id: MarketId) {
      this.market = id;
      uni.setStorageSync(STORAGE.market, id);
      this.apply();
    },
    /** 把货币与时区推给 shared/ 的模块级 holder（非组件上下文也要能用） */
    apply() {
      setCurrentCurrency(this.currency);
      setCurrentOffset(this.utcOffsetMinutes);
    },
  },
});
