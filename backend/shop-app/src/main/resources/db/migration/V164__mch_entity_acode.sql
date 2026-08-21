-- 店铺码的小程序码图（B-11.2.6）。
--
-- 为什么要落库而不是每次现调微信：wxacode.getUnlimited 生成的是**永久有效码**，
-- 每个 appid 总量有限（十万级）。每次请求都去要一张，几百个商家反复刷新页面
-- 就能把额度耗掉，而额度用尽之后**再也生成不出新码** —— 那时候新入驻的商家没有码可印。
--
-- 存 base64 而不是文件路径：一期没有对象存储（图片上传还落本地磁盘，多实例读不到）。
-- 接了对象存储之后这一列换成 URL，读写它的那一层不用动。
ALTER TABLE mch_entity
    ADD COLUMN acode_base64 MEDIUMTEXT NULL COMMENT '店铺小程序码 PNG 的 base64（不含 data: 前缀）';
