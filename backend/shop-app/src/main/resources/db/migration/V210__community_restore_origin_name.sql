-- 官方村（kind=VILLAGE）开通时存的是清理过的短名（「景滑」），不是 sys_region 里的
-- 原始官方名（「景滑村委会」）——提报表单在提交前做了一次 cleanVillageName()。
--
-- 商家/买家两端界面上仍然显示清理过的短名（Row.name 那一层做，不受这条影响）；
-- 但 cmt_community.name 这一列本身要跟国标区划的官方名一致，方便运营核对、
-- 数据导出对账时不用再反查一次 origin_code。
--
-- 只动 kind=VILLAGE 且 origin_code 能在 sys_region 里查到的那些 —— 地图开通的小区
-- （kind=ESTATE 或 origin_code 为空）没有官方名可对，不该被这条语句碰到。
UPDATE cmt_community c
JOIN sys_region r ON r.region_code = c.origin_code
SET c.name = r.name
WHERE c.kind = 'VILLAGE' AND c.origin_code IS NOT NULL;
