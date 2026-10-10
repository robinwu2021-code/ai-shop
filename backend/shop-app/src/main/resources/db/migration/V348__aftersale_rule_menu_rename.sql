-- 「极速退阈值配置」→「售后规则」。
--
-- 这一屏的职责变了：它此前只配极速退的金额上限与时限，现在还配四条售后时效
-- （商家响应 / 买家寄回 / 商家确认收货 / 平台介入承诺）。菜单名留在旧称上，
-- 运营找不到刚加的那半屏 —— 而他没有理由猜「时效在极速退阈值里」。
--
-- **只改显示名，不动 point_code**：编码是授权表引用的那一列
-- （sys_role_point.point_code），改它等于把已有授权静默指向别处。
UPDATE sys_function_point
SET name = '售后规则'
WHERE point_code = 'OPS_AFTERSALE__TAB_FASTREFUND';
