-- scope_code 装不下缓存键，写入全部失败（真机实测：INSERT 抛
-- `Data truncation: Data too long for column 'scope_code'`，GlobalExceptionHandler 把它
-- 转成 200 + 错误码返回，端上 `.catch(() => {})` 又把这个错误吞掉 —— 于是「反复进同一个社区
-- 缓存都没生效」这件事在界面上一点异常都不报，只有服务端日志里看得见）。
--
-- 根因：`VARCHAR(16)` 是按「街道 9 位 / 村 12 位区划码」估的，但已开通的社区走的是
-- 另一路键 —— `C` + 聚落号（`C202608240005390003971`，23 位），超了。
-- 聚落号本身在别处一律是 `VARCHAR(64)`（如 `mch_fulfillment_channel.community_no`），
-- 这里跟同一个口径，不再单独估一次。
ALTER TABLE geo_poi_cache
    MODIFY COLUMN scope_code  VARCHAR(64) NOT NULL,
    MODIFY COLUMN parent_code VARCHAR(32) NOT NULL;
