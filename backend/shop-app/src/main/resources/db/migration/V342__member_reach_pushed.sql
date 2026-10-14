-- 会员消息改由站内信承接（TDD-会员标签与定向营销 批 D）。
--
-- 批次头的「发出」从此是「进了买家小程序消息列表的人数」；其中真的推送到手机的另记一列。
-- 买家多在小程序、没有推送设备 —— 两个数不分开，商家看不出「发出 25」里有几条会亮屏。
ALTER TABLE mbr_reach_task ADD COLUMN pushed_count INT(11) NOT NULL DEFAULT 0 COMMENT '其中推送到手机的人数（有推送设备且推送成功）';
