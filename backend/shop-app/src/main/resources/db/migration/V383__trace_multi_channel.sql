-- 物流轨迹多渠道（TDD-物流轨迹多渠道 §2.4）。
--
-- 两条正交的渠道轴：数据源（谁去查）与展示渠道（用什么界面呈现）。
-- 这条迁移只给展示渠道与地图加列，数据源那一轴是纯配置，不落库。

-- ① 展示渠道的载荷。**刻意不写死微信**：列名是通用的 display_*，
--    加第三个展示渠道（支付宝小程序、承运商 H5…）不用再加列，只是 display_channel 多一个取值。
ALTER TABLE ful_shipment
    ADD COLUMN display_channel VARCHAR(32) DEFAULT NULL COMMENT '已备好载荷的展示渠道名（wx-plugin / self-map…）。空=还没备过',
    ADD COLUMN display_token VARCHAR(512) DEFAULT NULL COMMENT '该渠道的载荷。微信插件存 waybill_token——微信文档要求开发者自己存，且换取接口有调用次数上限(9300513)，不存就得反复换',
    ADD COLUMN display_fail_reason VARCHAR(255) DEFAULT NULL COMMENT '最近一次备载荷失败的原因（运单不存在/超配额/openid 不合法…）。给运营排查用，不给买家看';

-- ② 轨迹节点的城市中心坐标。**必须落库**：不存的话轮询存了轨迹、坐标丢了，
--    下次展示还得再查一遍快递100（按单计费，且同一单 30 分钟内重复查会锁单）。
--    只到行政区中心点，不是快件 GPS —— 所以地图只能画城市级，端上的说明文案也照这个写。
ALTER TABLE ful_shipment_trace
    ADD COLUMN lat_e6 INT DEFAULT NULL COMMENT '行政区中心纬度 ×1e6（gcj02）。空=这一节点没解析出行政区',
    ADD COLUMN lng_e6 INT DEFAULT NULL COMMENT '行政区中心经度 ×1e6（gcj02）',
    ADD COLUMN status_code VARCHAR(16) DEFAULT NULL COMMENT '承运商/聚合器的高级状态码（快递100 statusCode）。步骤条区分「派送中」用，端上不直接显示';
