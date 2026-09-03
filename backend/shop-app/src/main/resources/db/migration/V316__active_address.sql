-- 「当前生效位置」：用户存了多个位置（家 / 公司 / …），但任一时刻只按一个看货。
--
-- **不复用 usr_address.is_default。** 那一列的语义是「下单时预填哪个收货人」，
-- 是长期偏好；而生效位置是「现在按哪儿看货」，是这一次逛的上下文。
-- 两者常常相同，但给父母下单时就不同：切到父母家看货，默认收货人仍是自己。
-- 合成一列的后果是改了一个另一个跟着变，而用户不会预期这件事。
--
-- 放在 usr_account 而不是新开一张表：它是**用户级单值**，一个人只有一个当前位置。
ALTER TABLE usr_account
    ADD COLUMN active_address_id VARCHAR(32) NULL COMMENT '当前生效位置（usr_address.address_id）。与 is_default 是两回事';
