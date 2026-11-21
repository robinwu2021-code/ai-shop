-- 【待执行，不在 Flyway 目录】原定 V686。2026-11-20 用户定：正式库 v1 与 v2 共用菜单表，v1 前端没有支付中心页，
-- 改了 v1 的这 5 个菜单项会失效；先由 v2 前端把旧地址映射到支付中心（app/payment-center/module.ts 的 LEGACY_MENU_HREFS），
-- v1 下线或升级到支付中心之后，再把本文件按下一个空闲迁移号放回 db/migration 执行。
-- 支付中心独立成模块（TDD-支付中心运营页面 §模块边界）：V671 的 5 个支付入口原来挂在商户、订单模块的页签上
-- （进去是跳 near-pay /oms 的过渡页），改指向支付中心原生页签。只改 href：point_code、权限码、授权都不动，
-- 已授权的角色照旧可见；旧地址由前端转到新页签（LEGACY_TAB_REDIRECT）。可重复执行。
UPDATE sys_function_point SET href='/payment-center?tab=merchants', updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_MERCHANT__TAB_PAYMENT' AND href='/merchants?tab=payment';
UPDATE sys_function_point SET href='/payment-center?tab=onboardings', updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_MERCHANT__TAB_PAYMENT_ONBOARDING' AND href='/merchants?tab=payment-onboarding';
UPDATE sys_function_point SET href='/payment-center?tab=channels', updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_IAM__TAB_PAYMENT_CHANNELS' AND href='/merchants?tab=payment-channels';
UPDATE sys_function_point SET href='/payment-center?tab=orders', updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_ORDER__TAB_PAYMENTS' AND href='/orders?tab=payments';
UPDATE sys_function_point SET href='/payment-center?tab=exceptions', updated_at=CURRENT_TIMESTAMP WHERE point_code='OPS_ORDER__TAB_PAYMENT_EXCEPTIONS' AND href='/orders?tab=payment-exceptions';
