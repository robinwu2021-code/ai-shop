-- 号段序列表（ADR-035 订单号序列化）。
--
-- 只存一个游标：next_value = 已经发出去的最大序列值。领段时 += 段长，
-- 返回的新值就是这一段的上界（段 = [新值-段长+1, 新值]）。
-- 订单号把序列值格式化成 4 位展示，DB 这个值本身单调递增、永不回绕（BIGINT 够用到地老天荒）。
--
-- 为什么是号段不是「每笔 +1」：10 笔才一次 DB 往返；重启后内存段作废、游标已前进，领新段
-- 自然接上不重号；多实例各领各的段、天然不重叠（见 ADR-035「为什么号段」）。
CREATE TABLE sys_sequence
(
    seq_name   VARCHAR(32) NOT NULL COMMENT '序列名，如 ord_main',
    next_value BIGINT      NOT NULL DEFAULT 0 COMMENT '已发出的最大序列值；领段时 += 段长',
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (seq_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='号段序列表（ADR-035）：订单号等自增序列的 DB 游标';

-- 订单主单序列。子单不占序列（子单号 = 主单号 + 后缀）。
INSERT INTO sys_sequence (seq_name, next_value)
VALUES ('ord_main', 0);
