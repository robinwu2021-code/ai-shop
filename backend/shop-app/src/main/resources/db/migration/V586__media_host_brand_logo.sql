-- 品牌标识图片（V544 mch_brand.logo）补齐媒体引用登记对应的规范域名迁移。
-- V347/V369 已应用，保持原 checksum；仅替换含协议和结尾 / 的完整 COS 前缀。
-- 无旧域名、NULL 与相似域名均不变；语义与此前媒体域名迁移一致（ADR-026）。
UPDATE mch_brand SET logo = REPLACE(logo, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE logo LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
