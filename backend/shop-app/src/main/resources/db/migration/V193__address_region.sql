-- 收货地址的「省市区」一直存不进去：c-app 传的是一整串 region（「浙江省杭州市西湖区」），
-- 而 usr_address 只有 province/city/district 三列、后端也没有拆分逻辑 —— 这一串被静默丢弃，
-- 读回来是三个 null，页面上省市区那行就空着。线上这张表目前是 0 行，趁没有数据把它对齐。
-- 保留三列不动（将来接地址结构化时还用得上），新增一列存端上那一整串。
ALTER TABLE usr_address ADD COLUMN region VARCHAR(96) NULL COMMENT '省市区整串（端上单字段输入/地图选点回填）';
