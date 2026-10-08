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

/**
 * 在商品详情页挑货的那一刻（加购 / 立即购买 / 开团）：**这件货是从哪家店挑的就记哪家；
 * 不是从门户点进来的就忘掉这个主体的记录**，交给后端按默认规则落一家「在架 ∧ 有货」的店。
 *
 * 只靠进门户时记一笔是不够的：记录按**主体**存，而一个主体常有几家店各卖各的。
 * 2026-10-09 线上：买家在「虹选鲜果」门户买过柿子，再从首页点进同主体「虹选粮油」才上架的盐，
 * 结算带着鲜果店去预览 → 70076 已下架 → 页面退回本地估算，运费显示成写死的 6 元。
 * 后端对「他就是在这家店挑的」故意不换店也不拒（OrderServiceImpl#storesOfEntities），
 * 所以错的只能是端上 —— 不是在那家店挑的，就不该说是。
 */
export function pickedAt(entityNo: string, viaStore: string | undefined) {
  if (!entityNo) return;
  if (viaStore) {
    rememberStore(entityNo, viaStore);
    return;
  }
  try {
    const all = readAll();
    if (!(entityNo in all)) return;
    delete all[entityNo];
    uni.setStorageSync(KEY, all);
  } catch {
    /* 同上 */
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
