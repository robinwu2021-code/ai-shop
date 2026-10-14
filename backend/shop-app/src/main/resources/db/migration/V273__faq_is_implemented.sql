-- FAQ 那一页后端一直是有的（OpsMessageController：GET/POST /ops/faqs、
-- POST /ops/faqs/{faqNo}/published，三条判的都是 message:ticket:handle），
-- 而它的功能点从 V62 起标着 NOT_IMPLEMENTED、perm_code 为 null。
--
-- 后果是线上这一页在二级导航里**灰显、点不进去**（secondary-nav 按
-- backendStatus 渲染成「未实现」），而接口其实回 200 —— 生产实测过。
--
-- ⚠️ 三处要一起改，少一处这一页仍旧打不开：
--   1. backend_status  → IMPLEMENTED（否则导航照旧灰显）
--   2. perm_code / ui_perm_code → message:ticket:handle（端点真正判的那个码；
--      留 null 的话「不受权限约束」与「后端没做」两件事又混在一起了）
--   3. ops-web 的 perm-map.ts 里 "message:faq:update" 从 UNIMPLEMENTED 改成
--      映到同一个码 —— `can()` 是**先查映射后判通配**，映到 UNIMPLEMENTED
--      直接返回 false，**超管也看不见**。只发这条迁移等于白发。
UPDATE sys_function_point
SET backend_status = 'IMPLEMENTED',
    perm_code      = 'message:ticket:handle',
    ui_perm_code   = 'message:ticket:handle',
    updated_at     = NOW()
WHERE point_code = 'OPS_MESSAGE__TAB_FAQ'
  AND backend_status = 'NOT_IMPLEMENTED';
