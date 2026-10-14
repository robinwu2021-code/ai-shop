-- 履约方式回填（落地清单 F-1 的配套）。
--
-- prd_goods.fulfillments 此前是「有字段没入口」：建商品时被写死成 ["STORE_PICKUP"]，
-- 商家改不了。所以那个值**从来不表示「只支持到店自提」**，它只是个占位 ——
-- 事实上这些商品一直在被下成快递单、配送单，全流程正常。
--
-- F-1 给下单加了「用户选的方式该商品必须支持」这道校验。若照原样启用，
-- 存量商品会一夜之间只剩到店自提可选 —— 那不是修复，是把占位数据当成了业务事实。
--
-- 所以把占位值回填成「四种都支持」，与这些商品**实际一直被怎么卖**保持一致；
-- 往后由商家主动收窄。放宽再收窄是安全方向，反过来会凭空拦掉正在成交的单。
UPDATE prd_goods
SET fulfillments = '["STORE_PICKUP","NEIGHBOR_PICKUP","MERCHANT_DELIVERY","EXPRESS"]'
WHERE fulfillments IS NULL
   OR fulfillments = ''
   OR fulfillments = '["STORE_PICKUP"]';
