-- 退款到账的微信订阅（WXSUB）关掉：它一直空转（通知节点清单 review，2026-10-05）。
--
-- V156 给 AFTER_SALE_REFUNDED 配了 WXSUB=1，但**本小程序公共模板库里没有「退款」这一类**
-- （一次性订阅只对政务民生/医疗等特定类目开放），线上 env 也从来没有 WX_TPL_REFUNDED ——
-- 这一行从上线起就空转：看配置以为在发微信订阅，实际只走站内信。
--
-- 关掉让配置说真话。**买家不会因此少收到退款提醒** —— 微信支付的退款本来就由
-- 微信支付自己推一条到账消息，不靠我们的订阅。
--
-- 将来若模板库开放了退款类目、补了模板，运营端把这一行打开即可（notify_scene_channel 运营可配）。

UPDATE notify_scene_channel
SET enabled = 0
WHERE scene_code = 'AFTER_SALE_REFUNDED' AND audience = 'C_USER' AND channel = 'WXSUB';
