-- FAQ 那一页后端一直是有的（OpsMessageController：GET/POST /ops/faqs、
-- POST /ops/faqs/{faqNo}/published），而它的功能点从 V62 起就标着 NOT_IMPLEMENTED。
--
-- 这条标记此前**没有任何后果** —— backend_status 谁都不读，只在 IAM 那一页当徽章。
-- 同一笔改动把它接进了菜单（PermConfigServiceImpl.menu 不再返回 NOT_IMPLEMENTED 的点），
-- 于是陈标从「没有意义」变成「会把一个能用的页面藏掉」。先把它改对。
--
-- 这正是接线的价值：一列没人读的状态一定会锈，而锈在哪儿平时看不出来。
UPDATE sys_function_point
SET backend_status = 'IMPLEMENTED',
    updated_at     = NOW()
WHERE point_code = 'OPS_MESSAGE__TAB_FAQ'
  AND backend_status = 'NOT_IMPLEMENTED';
