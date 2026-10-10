// C 端 mock 的门面：把各域拼成一份完整实现。
//
// <p><b>类型标注在这里，就是那道闸</b>：`ShopApi` 要求 86 个方法一个不少，
// 拆分时漏掉任何一个，这一行都过不了 `vue-tsc`。
import type { ShopApi } from "../contract";
import { userMock } from "./user";
import { communityMock } from "./community";
import { catalogMock } from "./catalog";
import { tradeMock } from "./trade";
import { aftersaleMock } from "./aftersale";
import { marketingMock } from "./marketing";
import { merchantMock } from "./merchant";
import { groupMock } from "./group";
import { messageMock } from "./message";

export const mockApi: ShopApi = {
  ...userMock,
  ...communityMock,
  ...catalogMock,
  ...tradeMock,
  ...aftersaleMock,
  ...marketingMock,
  ...merchantMock,
  ...groupMock,
  ...messageMock,
};
