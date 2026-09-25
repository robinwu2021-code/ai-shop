-- 图片地址从 COS 公网域名迁到规范地址 img.hxmall.top（ADR-026，TDD-图片走服务器与流量切换 T5）。
--
-- 为什么：COS 公网域名直连 = 每一字节都是 COS 外网下行流量，2026-09-25 因此欠费停服。
-- 规范地址 img.hxmall.top 经应用服务器（nginx 走内网取 COS），将来切到图片服务器（cdn.hxmall.top）
-- 只改配置 shop.media.delivery，不再动数据。
--
-- 范围 = 回收扫描的媒体引用登记表（CoreMediaRefs / MerchantMediaRefs / SettleMediaRefs）的全部 25 个字段。
-- 只换完整前缀（含 https:// 与结尾的 /），JSON 数组与富文本里嵌着的也一起换。
-- 没有 COS 地址的环境（本地、测试）这里每条都是 0 行，无副作用。
-- 私有图存的是裸 key，不含域名，不受影响。
-- 反向迁移：把两个字面量对调再跑一遍。

UPDATE stl_purchase_invoice SET image_url = REPLACE(image_url, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE image_url LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_goods SET cover = REPLACE(cover, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE cover LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_goods SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_goods SET detail_images = REPLACE(detail_images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE detail_images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_topic SET cover = REPLACE(cover, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE cover LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_spu_std SET cover = REPLACE(cover, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE cover LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE prd_spu_std SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE ord_item SET cover = REPLACE(cover, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE cover LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE ord_after_sale SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE rvw_review SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE rvw_appeal SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE rvw_review SET avatar = REPLACE(avatar, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE avatar LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE rvw_review SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE usr_account SET avatar = REPLACE(avatar, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE avatar LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mkt_group_buy SET cover = REPLACE(cover, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE cover LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mkt_request SET images = REPLACE(images, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE images LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE cnt_material SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE cnt_post SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE cnt_question SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE notify_template SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE notify_ticket SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mch_entity SET logo = REPLACE(logo, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE logo LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mch_entity_apply SET qualifications = REPLACE(qualifications, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE qualifications LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mch_qualification SET image_url = REPLACE(image_url, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE image_url LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
UPDATE mch_store_audit SET content = REPLACE(content, 'https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/', 'https://img.hxmall.top/') WHERE content LIKE '%https://hxmall-merchant-1301656997.cos.ap-guangzhou.myqcloud.com/%';
