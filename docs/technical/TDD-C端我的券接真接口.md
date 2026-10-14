# TDD-C端我的券接真接口

状态：已实现（2026-09-20）
档位：2（端上接一个既有端点 · 无库表 · i18n）
依据：用户 2026-09-20「c 端的优惠券有问题，结账能看到优惠券，但是优惠券不能点击，看不到优惠券的具体信息，优惠券入口也看不到优惠券的信息」
创建日期：2026-09-20

## 1. 实测：错在哪

**端上把「领券中心」当成了「我的券」。** 后端有两个端点：

| 端点 | 回答的问题 | 返回 |
|---|---|---|
| `GET /mp/coupon` | 现在**能领**哪些券（游客也能看） | `CouponVO[]`（带 `received` 标记） |
| `GET /mp/coupon/mine` | 我手里**有**哪些券 | `UserCouponVO[]`（带 `status` / `usableNow` / `usedAt`） |

端上两处都只用了前者：

- `pages/coupons`（我的券）：`couponList().filter(c => c.received)`
- `pages/order-confirm`（结算）：`coupons = await couponList()`，再按 `received && endAt > now && 门槛` 过滤

后果不是「少一点信息」：

1. **券会从「我的券」里消失。** 活动下架、被抢光（`remain = 0`）、或过期之后，
   那张券不在 `center()` 的返回里了 —— 而它还在用户手里。
2. **「已使用 / 已过期」两栏基本恒空**：领券中心不回答「这张用没用过」。
3. **结算页同理**：券在手却不在可用列表里，用户会以为券丢了。

`/mp/coupon/mine` 因此一直挂在闸门的「后端做了没入口」清单上。

**另一件事实**：线上券表此刻**全是空的**（`mkt_coupon` 0 行、`mkt_user_coupon` 0 行）。
所以用户此刻看到的「优惠券 · 无可用、点不动」本身是对的 —— 没券可选。
但上面那三条是真缺陷，有券之后就会显形。

## 2. 契约

不新增端点。端上补一条调用：

- `myCoupons(): Promise<UserCoupon[]>` → `GET /mp/coupon/mine`（要登录）
- 端上 `UserCoupon` 类型已经有了（`userCouponNo / coupon / status / usableNow / receivedAt / usedAt`），不用改。

## 3. 模块

- `c-app/src/api`：`endpoints` · `contract` · `http` · `mocks/marketing` · `scripts/gen-openapi.mjs`（RESPONSE_TYPES）
- `pages/coupons`：三栏数据改用 `myCoupons()`；商家券（到店码）那一段不动
- `pages/order-confirm`：可用券改用 `myCoupons()`；**门槛与是否可用以服务端的 `usableNow` 为准**，
  端上只再判一次这单的金额门槛（预览金额会变）
- 券选择弹层每一行补**门槛与到期**：这正是用户说的「看不到具体信息」

## 4. 测试

`c-app/tests/my-coupons.test.ts`：

1. 我的券页调的是 `myCoupons`，不是 `couponList`；
2. 已用 / 已过期两栏按 `status` 分，不靠 `endAt` 猜；
3. 结算页的可用券来自 `myCoupons`；
4. 券选择弹层每行带门槛与到期。

消融：把 `myCoupons` 换回 `couponList` → 第 1、3 条必红。

## 5. 风险

- 「领券中心」这条线还在（商品详情页的「领券」那一行走 `couponList`），本次不动它。
- 线上没有券数据，所以**这一版改完在真机上看不出差别**。验证只能靠单测与 mock；
  真要眼见为实，得先在运营端建一张券并发给这个账号。

## 实现 → 需求（测试真实输出）

- `c-app/tests/my-coupons.test.ts`：8 跑 / 0 红。
  消融：我的券页换回 `couponList` → 红（「我的券页调 myCoupons」那条）。
- c-app 全量 271 跑 / 0 红；`vue-tsc` 干净。
- H5 mock 实跑：「我的券」三栏都有样本 —— 可用栏的「新人首单券」带出了
  「全场可用 · ¥5.00 · 至 09-27」，「已用」栏出现了「生鲜满减 · 已用」（此前这一栏恒空）。

## 偏差说明

- **线上此刻没有任何券数据**（券模板 0 行、用户券 0 行），所以这一版在真机上看不出差别。
  用户报的「结账看不到券信息、点不动」在没有券时本身是对的行为；
  这次修的是「有券之后会错」的那三条。真要眼见为实，得先在运营端建一张券并发给这个账号。
- 领券中心那条线（商品详情页的「领券」）没有动。
