# v2 B 端权限验证 2026-11-19 18:50:05
191 个 GET；店员（CLERK）持有 13 个权限码；门店 ST261119BE70D8B9C9

## 店员：网关的判定与真实响应
- 其它：在网关判权之前就被准入挡住（HTTP 403）：15
- 其它：店员该拒且被拒：62
- 其它：店员该放且放行：23
- 其它：网关不按权限码判（LOGIN）：35
- 店主：http 502：6
- 支付：在网关判权之前就被准入挡住（HTTP 403）：8
- 支付：店员该拒且被拒：28
- 支付：店员该放且放行：7
- 支付：网关不按权限码判（LOGIN）：13

## 支付入口在网关上的类别
- POINT：43
- LOGIN：13

## 店主（全部权限）仍被拒：35 条 —— 这些拒绝都不是网关按角色判出来的

### 模块自己的准入（HTTP 403）：14 条（其中支付相关 1 条）
- [支付] /biz/pay/v2/channel-account-claims（网关类别 POINT）→ http 403 code 10403
- /biz/print/action-logs（网关类别 POINT）→ http 403 code 10403
- /biz/print/agents（网关类别 POINT）→ http 403 code 10403
- /biz/print/changes（网关类别 POINT）→ http 403 code 10403
- /biz/print/health（网关类别 POINT）→ http 403 code 10403
- /biz/print/incidents（网关类别 POINT）→ http 403 code 10403
- /biz/print/jobs（网关类别 POINT）→ http 403 code 10403
- /biz/print/printers（网关类别 POINT）→ http 403 code 10403
- /biz/print/requests（网关类别 POINT）→ http 403 code 10403
- /biz/print/routes（网关类别 POINT）→ http 403 code 10403
- /biz/print/scene-policies（网关类别 POINT）→ http 403 code 10403
- /biz/print/scenes（网关类别 POINT）→ http 403 code 10403
- /biz/print/simulations（网关类别 POINT）→ http 403 code 10403
- /biz/print/templates（网关类别 POINT）→ http 403 code 10403

### 门店方案与功能准入（过滤器，排在网关判权之前）：11 条（其中支付相关 6 条）
- [支付] /biz/pay/app-grant（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/qr-code（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/refund（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/statement（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/accounts（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/records（网关类别 POINT）→ http 403 code 70006
- /biz/card-product-presets（网关类别 POINT）→ http 403 code 70006
- /biz/entitlement-templates（网关类别 POINT）→ http 403 code 70006
- /biz/entitlement-usage（网关类别 POINT）→ http 403 code 70006
- /biz/pricing-tax-settings（网关类别 POINT）→ http 403 code 70006
- /biz/store-sales-catalog（网关类别 POINT）→ http 403 code 70006

### 支付域的调用方矩阵与门店范围（业务代码里）：7 条（其中支付相关 7 条）
- [支付] /biz/payment/app-grant（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/links（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/qr-code（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/refund（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/statement（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/store-method（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/store-method-preference（网关类别 POINT）→ http 200 code 70082

### 业务代码里的范围检查：3 条（其中支付相关 0 条）
- /biz/pickup/orders（网关类别 POINT）→ http 200 code 10403
- /biz/pickup/overview（网关类别 POINT）→ http 200 code 10403
- /biz/pickup/picking（网关类别 POINT）→ http 200 code 10403

## 结论：店员的判权全部一致
