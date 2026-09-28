-- 新品开售提醒（TDD-C 端裂变与商家招募 §10）。
--
-- 一、prd_goods 加「已通知过」时间戳
--
-- 幂等挂在这一列上，而不是挂在「上架」这个动作上：MerchantGoodsServiceImpl 里
-- setOnSale(true) 有五处调用点，多数是「下架后重新上架」。挂在动作上的话，
-- 商家反复上下架就能给收藏者刷屏，而且加第六处调用点的人不会知道要带上这件事。
-- 挂在列上则天然幂等：为空才发，发完写上，第六处调用点什么都不用改。
--
-- 可空：存量商品一行都不用补 —— 它们不是新品，本来就不该触发通知。

ALTER TABLE prd_goods
    ADD COLUMN new_notified_at BIGINT DEFAULT NULL COMMENT '新品开售提醒已发送时间（毫秒）。为空=还没发过';

-- 二、场景 × 通道种子
--
-- INAPP 默认关（enabled=0），只开 WXSUB。上新是营销不是事实记录，
-- 塞进消息中心会稀释「到货了去取」那几条；而订阅消息那一路有用户当场点过的授权把关，
-- 且一次授权只够一条，额度本身就是限流。运营要开站内信，后台那一屏打开即可，不用发版。
--
-- WHERE NOT EXISTS 的写法照 V156：重复执行不会撞唯一键。

INSERT INTO msg_scene_channel (scene_code, audience, channel, enabled, push_level, created_at, updated_at)
SELECT t.scene_code, t.audience, t.channel, t.enabled, t.push_level, NOW(), NOW()
FROM (
    SELECT 'NEW_GOODS_ON_SALE' AS scene_code, 'C_USER' AS audience, 'INAPP' AS channel, 0 AS enabled, 'NORMAL' AS push_level UNION ALL
    SELECT 'NEW_GOODS_ON_SALE', 'C_USER', 'WXSUB', 1, 'NORMAL' UNION ALL
    SELECT 'NEW_GOODS_ON_SALE', 'C_USER', 'PUSH', 0, 'NORMAL'
) t
WHERE NOT EXISTS (
    SELECT 1 FROM msg_scene_channel m
    WHERE m.scene_code = t.scene_code AND m.audience = t.audience AND m.channel = t.channel
);
