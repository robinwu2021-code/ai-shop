// 「这个主体我在逛哪家店」（TDD-C端门店化与门店门户 §2.7）。
//
// 进门户时记下（主体号 → 门店号），结算时随预览 / 能力 / 下单一起带给后端：
// 在 B 店门户里挑的货由 B 店履约。不带的话后端按自提点 → 默认店落单，
// 于是页面上一路写着 B 店，单却落到默认的 A 店 —— 价格、库存、履约全按 A 走，不报错。
//
// 存在 uni 的存储里而不是 localStorage：App 运行时没有 localStorage（见 H5 对 App 的两个坑）。
// 读写失败一律当成「没记过」—— 这是一个偏好，丢了只是回到改造前的落店规则。
import type { StoreChoice } from "@shared/types";

const KEY = "c.store.choice";

function readAll(): Record<string, string> {
  try {
    const v = uni.getStorageSync(KEY) as unknown;
    return v && typeof v === "object" ? (v as Record<string, string>) : {};
  } catch {
    return {};
  }
}

/** 进了这家店的门户：这个主体以后的单优先落到它 */
export function rememberStore(entityNo: string, storeNo: string) {
  if (!entityNo || !storeNo) return;
  try {
    uni.setStorageSync(KEY, { ...readAll(), [entityNo]: storeNo });
  } catch {
    /* 存不下就不存 —— 见文件头 */
  }
}

/** 这几个主体各自在逛哪家店。一个都没记过返回 undefined（请求里不带这个字段，与改造前逐字相同） */
export function storeChoicesFor(merchantNos: readonly string[]): StoreChoice[] | undefined {
  const all = readAll();
  const out = [...new Set(merchantNos)]
    .filter((m) => !!m && !!all[m])
    .map((merchantNo) => ({ merchantNo, storeNo: all[merchantNo]! }));
  return out.length ? out : undefined;
}
