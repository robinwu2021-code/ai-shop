-- V67：建员工 / 多角色 / 功能点是否有界面入口。
--
-- 两件事，都很小，但各自堵一个具体的坑：

-- ① 首登强制改密。
--
-- 新建员工时后端生成随机初始密码、**只在创建响应里返回一次**。
-- 没有这一列的话，那个密码会一直有效 —— 而它在创建那一刻经过了
-- 建号人的屏幕、剪贴板，可能还有一条聊天记录。
--
-- 默认 0：存量账号不受影响。只有 createStaff 写 1。
ALTER TABLE sys_ops_staff ADD COLUMN must_change_password TINYINT(4) NOT NULL DEFAULT 0
    COMMENT '首登必须改密。建号时后端生成的随机初始密码只是「拿到账号」的凭据，不是长期口令';

-- ② 功能点有没有界面入口。
--
-- 现在靠 group_name='无界面入口' 这个**中文字符串**判断 ——
-- 改一次文案，所有判断一起错，而且不会报错。
--
-- 三种取值不合并成一个布尔：
--   MENU 有 href、页面内 ACTION 按钮本来就没 href（这是设计）、
--   而「后端有能力、前端没做页面」是缺口。**后两者都没有 href，但性质相反**，
--   合成一个布尔就再也分不出「本来就不该有」和「还没做」。
ALTER TABLE sys_function_point ADD COLUMN ui_kind VARCHAR(16) NOT NULL DEFAULT 'MENU'
    COMMENT '界面形态：MENU=菜单项 / INLINE=页面内按钮（本来就没有独立入口） / NONE=后端有能力但前端没做页面';

UPDATE sys_function_point SET ui_kind = 'MENU'   WHERE point_type = 'MENU';
UPDATE sys_function_point SET ui_kind = 'INLINE' WHERE point_type = 'ACTION' AND group_name = '页面内操作';
UPDATE sys_function_point SET ui_kind = 'NONE'   WHERE point_type = 'ACTION' AND group_name = '无界面入口';

-- ③ **本想在这里修 sys_role_member 的唯一键，收手了。**
--
-- uk_role_member 是 (end_code, subject_no, role_code, scope_no)，而运营端的
-- scope_no 恒为 NULL —— MySQL 的 UNIQUE 把 NULL 当互不相等，所以这个键从来没生效过。
--
-- 但把 scope_no 改成 NOT NULL DEFAULT '' 之后，键会立刻在一个**正常操作**上炸：
-- 撤销角色走的是逻辑删除（deleted=1，行还在），重新授予时插新行 —— 撞键。
-- 一个会在「把某人的角色去掉再加回来」时报错的唯一键，比现在这个洞更坏。
--
-- 要修得先定撤销的语义：物理删，还是复用那行把 deleted 翻回 0。
-- 那是独立一批，已单独登记。**这里只留证据，不动结构。**
