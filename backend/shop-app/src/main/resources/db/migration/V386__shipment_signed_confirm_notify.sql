-- 签收闭环（TDD-物流域-完整方案 §4.2 批 A）。
--
-- 微信不会把「已签收」回调给开发者（那三条节点消息是推给买家的），签收仍要我们自己查。
-- 查到之后要做两件事，各需要一个落点：
--   ① ful_shipment.signed_at —— 签收那一刻的时间戳（毫秒）。
--      微信确认收货提醒必填 received_time，且**必须晚于发货时间**，否则回 10060029。
--      事后从轨迹节点反推不可靠（节点会被后续查询追加、顺序不保证），所以推进状态那一刻就记下来。
--   ② trd_shipping_upload.confirm_notified_at —— 已经提醒过了（毫秒）。
--      微信规定**每个订单仅可调用一次**，而一个支付单可能对应多张子单/运单，
--      所以幂等标记放在「支付单」这一层（这张表本来就是按 order_no 一行），不放运单上。
ALTER TABLE ful_shipment ADD COLUMN signed_at BIGINT DEFAULT NULL COMMENT '签收时间（毫秒）；推进到 DELIVERED 那一刻记下';
ALTER TABLE trd_shipping_upload ADD COLUMN confirm_notified_at BIGINT DEFAULT NULL COMMENT '已调用微信确认收货提醒的时间（毫秒）；每单仅一次';
