-- 积分补差（落地清单 P2-8）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 修的是一个正在发生的错：积分抵扣的成本被转嫁给了商家
-- ─────────────────────────────────────────────────────────────────────────────
-- ord_sub_order.points_deduct 的注释白纸黑字写着
--   「平台内部字段，不下发商家端 —— **商家按订单全额收款**」
-- 而实际链路是：
--   下单   sub.pay_amount = 货款 + 运费 − 优惠 − **积分抵扣**
--   结算   gross          = pay_amount + platform 补贴          ← 少了积分抵扣那一块
-- 于是买家用积分抵掉的那部分，**从商家的货款里出**。
--
-- 通道侧本来就有这一步（微信 /v3/ecommerce/subsidies、支付宝退营销补差），
-- PayGateway.subsidy 与两个通道实现也都写好了 —— **零调用方**。
-- 也就是说：接口在、实现在、钱没打。
--
-- 补差额落快照而不是每次从子单现算：费率与积分规则都会变，
-- 而「这单当初补了多少」必须能原样查回来。与 commission_rate 同一个道理。
ALTER TABLE stl_bill ADD COLUMN subsidy_minor BIGINT(20) NOT NULL DEFAULT 0
    COMMENT '积分抵扣补差额（分）：平台补进二级商户，让商家按全额收款';
ALTER TABLE stl_bill ADD COLUMN subsidy_at BIGINT(20) DEFAULT NULL
    COMMENT '补差成功时刻；空 = 未补或无需补';
