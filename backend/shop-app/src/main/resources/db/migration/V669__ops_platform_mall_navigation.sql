-- 第3批第一步：平台商城既有入口归并，仅更新展示字段；两套套餐保留原路由、模型及授权。
UPDATE sys_function SET name='平台商城',sort=7,updated_at=NOW() WHERE function_code='OPS_COMMERCE' AND end_code='OPS';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='主体套餐',group_name='套餐方案',sort=10,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_PLANS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='门店套餐',group_name='套餐方案',sort=20,updated_at=NOW() WHERE point_code='OPS_COMMERCE' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='档位定义',group_name='套餐方案',sort=30,updated_at=NOW() WHERE point_code='OPS_MERCHANT__TAB_PLAN_DEFS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='平台商品',group_name='商品目录',sort=40,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_PRODUCTS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='商品分类',group_name='商品目录',sort=50,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_CATEGORIES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='订阅权益',group_name='权益管理',sort=60,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_SOURCES' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='购买订单',group_name='商城订单',sort=70,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_ORDERS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='运营代购',group_name='商城订单',sort=80,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_ASSISTED' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='核款审核',group_name='商城订单',sort=90,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_CLAIMS' AND point_type='MENU';
UPDATE sys_function_point SET function_code='OPS_COMMERCE',name='收款配置',group_name='销售设置',sort=100,updated_at=NOW() WHERE point_code='OPS_COMMERCE__TAB_COLLECTION' AND point_type='MENU';
