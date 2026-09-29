-- 运费进结算（TDD-快递100商家寄件 §9 · AC21-AC24）
--
-- 此前 gross 含运费而佣金按 gross 算 —— 平台对代收的运费也抽了佣金；
-- 且平台代寄（平台付快递费）与商家自寄（商家自付）在结算侧一视同仁。
--
-- 改口径的代价此刻是零：2026-09-29 查生产，freight_amount > 0 的订单 0 条。
-- 所以不回填存量，新单按新口径。

ALTER TABLE stl_bill
    ADD COLUMN freight_income_minor BIGINT NOT NULL DEFAULT 0
        COMMENT '买家付的运费（代收）。gross 不含它',
    ADD COLUMN freight_cost_minor BIGINT NOT NULL DEFAULT 0
        COMMENT '平台实付快递费；商家自寄为 0',
    ADD COLUMN freight_ship_mode VARCHAR(32) NULL
        COMMENT 'PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为 NULL',
    ADD COLUMN freight_diff_reason VARCHAR(32) NULL
        COMMENT '实付与代收有差额时的原因：OVERWEIGHT 超重 / REGION_SURCHARGE 地区加收';

-- 对账页按「发货方式 + 期次」查，两列一起建
CREATE INDEX idx_stl_bill_freight ON stl_bill (freight_ship_mode, accrued_at);
