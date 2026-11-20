# v2 接口验证 2026-11-19 22:41:12
四个测试账号都换到了会话：运营管理员、运营客服、买家、店主用户。

## 运营端：235 个 GET；受限角色持有 17 个权限码
- 受限角色：放行（LOGIN）：6
- 受限角色：放行（POINT）：38
- 受限角色：被拒（POINT）：187
- 支付运营面（公网入口不放行，未打）：4
- 按权限式算出该拒 187 条，入口实际拒了 187 条

## C 端：61 个 GET
- 匿名时 401 的 32 条；带买家令牌后仍 401 的 0 条
- 带令牌的响应：http 200 61

## B 端：191 个 GET（店主用户，还没有门店）
- http 403 code 70006：159
- http 200 code 0：21
- http 502 code 10505：6
- http 200 code 10400：5

## 钥按端分：拿一端的钥签另一端的票据
- 运营端的钥 签 B 端的票据：被拒（code 10401）
- 运营端的钥 签 C 端的票据：被拒（code 10401）
- B 端的钥 签 OPS 端的票据：被拒（code 10401）
- B 端的钥 签 C 端的票据：被拒（code 10401）
- C 端的钥 签 OPS 端的票据：被拒（code 10401）
- C 端的钥 签 B 端的票据：被拒（code 10401）

## 跨端：拿一端的令牌打另外两端
- 买家令牌 → /ops/auth/me：http 401 code 10401 被拒
- 买家令牌 → /biz/goods：http 401 code 10401 被拒
- 店主令牌 → /ops/auth/me：http 401 code 10401 被拒
- 店主令牌 → /mp/order/list：http 401 code 10401 被拒
- 运营令牌 → /biz/goods：http 401 code 10401 被拒
- 运营令牌 → /mp/order/list：http 401 code 10401 被拒

## 结论：6 处要看
- B 端 502 /biz/pay/v2/capability-progress code=10505
- B 端 502 /biz/pay/v2/memberships code=10505
- B 端 502 /biz/pay/v2/merchant-channels code=10505
- B 端 502 /biz/pay/v2/merchant-creations/context code=10505
- B 端 502 /biz/pay/v2/merchant-profile-revisions code=10505
- B 端 502 /biz/pay/v2/onboardings code=10505
