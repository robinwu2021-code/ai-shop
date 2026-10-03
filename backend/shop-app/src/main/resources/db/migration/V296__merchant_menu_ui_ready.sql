-- 商家治理九项标为「已交付」：后端早就实现了，只有交付标记没跟上。
--
-- 这九项（门店档案 / 类目授权 / 资质档案 / 准入与保证金 / 无照自营风险 /
-- 认证标 / 信用档案 / 违规封禁 / 增值包）的 backend_status 都是 IMPLEMENTED，
-- 而 ui_ready=0、nav.ts 里也没有 ready:true。
--
-- **这个标记不控制可见性**（前端渲染不读它），所以线上一直能用 —— 但它是守卫的判据：
-- 「菜单项必须有对应 sys_function_point 迁移」那道闸门会**直接跳过**未标 ready 的项。
-- 也就是说这九项一直处于免检状态：哪天菜单行丢了也不会有人被拦住。
--
-- 与 nav.ts 同批改，两处保持一致；不动 backend_status（本来就是对的）。
UPDATE sys_function_point
   SET ui_ready = 1, updated_at = NOW()
 WHERE point_type = 'MENU'
   AND backend_status = 'IMPLEMENTED'
   AND href IN ('/merchants?tab=stores',
                '/merchants?tab=categories',
                '/merchants?tab=qualifications',
                '/merchants?tab=admission',
                '/merchants?tab=mode-risk',
                '/merchants?tab=verify',
                '/merchants?tab=credit',
                '/merchants?tab=ban',
                '/merchants?tab=plans');
