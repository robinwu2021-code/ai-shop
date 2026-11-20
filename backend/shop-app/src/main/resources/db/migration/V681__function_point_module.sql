-- 功能点归哪个模块（TDD-网关层与统一权限 §2.6，开发计划 F 第二步）。
-- 存的是源码事实：按权限码的前缀算出来的归属，跨部署稳定。由网关在启动时回填与校正，这里不写数据。
-- 「这套部署装没装这个模块」是部署状态，不入库，由菜单接口现算 —— 与 gated_by 同一个口径。
ALTER TABLE sys_function_point ADD COLUMN module_code VARCHAR(32) NULL COMMENT '提供这个功能点的模块编码；没有权限码的点为空';
