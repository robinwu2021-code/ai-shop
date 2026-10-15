-- 历史主体收藏保留，读取时按旧链接规则解析默认门店；新收藏明确落到门店。
ALTER TABLE usr_store_favorite
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '实际收藏门店，历史主体收藏为空',
    DROP INDEX uk_user_entity,
    ADD UNIQUE KEY uk_user_store (user_no, store_no);
