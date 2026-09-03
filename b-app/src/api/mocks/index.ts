// B 端 mock 的门面：把各域拼成一份完整实现。
//
// <p><b>类型标注在这里，就是那道闸</b>：`MerchantApi` 要求 228 个方法一个不少，
// 拆分时漏掉任何一个，这一行都过不了 `vue-tsc` —— 而漏掉的表现本来是
// 「页面上某个按钮点了没反应」，不会有任何报错。
import type { MerchantApi } from "../contract";
import { accountMock } from "./account";
import { storeMock } from "./store";
import { dashboardMock } from "./dashboard";
import { productMock } from "./product";
import { orderMock } from "./order";
import { groupMock } from "./group";
import { reviewMock } from "./review";
import { marketingMock } from "./marketing";
import { settleMock } from "./settle";
import { messageMock } from "./message";
import { inventoryMock } from "./inventory";

export const mockApi: MerchantApi = {
  ...accountMock,
  ...storeMock,
  ...dashboardMock,
  ...productMock,
  ...orderMock,
  ...groupMock,
  ...reviewMock,
  ...marketingMock,
  ...settleMock,
  ...messageMock,
  ...inventoryMock,
};
