# v2 B 端权限验证 2026-11-19 14:03:34
188 个 GET；店员（CLERK）持有 13 个权限码；门店 ST261119BE70D8B9C9

## 店员：网关的判定与真实响应
- 其它：在网关判权之前就被准入挡住（HTTP 403）：31
- 其它：店员该拒且被拒：53
- 其它：店员该放且放行：22
- 其它：网关不按权限码判（LOGIN）：29
- 店主：http 502：5
- 支付：在网关判权之前就被准入挡住（HTTP 403）：28
- 支付：店员该拒且被拒：13
- 支付：店员该放且放行：7
- 支付：网关不按权限码判（LOGIN）：5

## 支付入口在网关上的类别
- POINT：41
- LOGIN：12

## 店主（全部权限）仍被拒：69 条 —— 这些拒绝都不是网关按角色判出来的

### 门店方案与功能准入（过滤器，排在网关判权之前）：47 条（其中支付相关 26 条）
- [支付] /biz/management/stored-value/financial-capability（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/management/stored-value/financial-resources（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/management/stored-value/settlement-bills（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/management/stored-value/settlement-entity-candidates（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/management/stored-value/store-issuer-summary（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/pay/app-grant（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/qr-code（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/refund（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/pay/statement（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/batch（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/bills（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/daily-flow（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/income（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/invoice-pending（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/invoice-title（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/invoices（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/rate-card（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/settle/statement（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/account-directory（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/accounts（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/brand-capability（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/stored-value/context（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/customer-candidates（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/governance-capability（网关类别 LOGIN）→ http 403 code 70006
- [支付] /biz/stored-value/plans（网关类别 POINT）→ http 403 code 70006
- [支付] /biz/stored-value/records（网关类别 POINT）→ http 403 code 70006
- /biz/card-product-presets（网关类别 POINT）→ http 403 code 70006
- /biz/communities/applies（网关类别 POINT）→ http 403 code 70006
- /biz/cross-store/compare（网关类别 POINT）→ http 403 code 70006
- /biz/cross-store/overview（网关类别 POINT）→ http 403 code 70006
- /biz/customers（网关类别 POINT）→ http 403 code 70006
- /biz/delivery/rule（网关类别 POINT）→ http 403 code 70006
- /biz/deposit（网关类别 POINT）→ http 403 code 70006
- /biz/deposit/txns（网关类别 POINT）→ http 403 code 70006
- /biz/entitlement-templates（网关类别 POINT）→ http 403 code 70006
- /biz/entitlement-usage（网关类别 POINT）→ http 403 code 70006
- /biz/fulfillment/carriers（网关类别 POINT）→ http 403 code 70006
- /biz/geo/estates（网关类别 LOGIN）→ http 403 code 70006
- /biz/geo/estates/counts（网关类别 LOGIN）→ http 403 code 70006
- /biz/merchant/debt（网关类别 POINT）→ http 403 code 70006
- /biz/pricing-tax-settings（网关类别 POINT）→ http 403 code 70006
- /biz/regions（网关类别 LOGIN）→ http 403 code 70006
- /biz/regions/path（网关类别 LOGIN）→ http 403 code 70006
- /biz/regions/search（网关类别 LOGIN）→ http 403 code 70006
- /biz/regions/villages（网关类别 LOGIN）→ http 403 code 70006
- /biz/store-sales-catalog（网关类别 POINT）→ http 403 code 70006
- /biz/store-spec-dims（网关类别 POINT）→ http 403 code 70006

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

### 支付域的调用方矩阵与门店范围（业务代码里）：5 条（其中支付相关 5 条）
- [支付] /biz/payment/app-grant（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/links（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/qr-code（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/refund（网关类别 POINT）→ http 200 code 70082
- [支付] /biz/payment/statement（网关类别 POINT）→ http 200 code 70082

### 业务代码里的范围检查：3 条（其中支付相关 0 条）
- /biz/pickup/orders（网关类别 POINT）→ http 200 code 10403
- /biz/pickup/overview（网关类别 POINT）→ http 200 code 10403
- /biz/pickup/picking（网关类别 POINT）→ http 200 code 10403

## 结论：店员的判权全部一致
