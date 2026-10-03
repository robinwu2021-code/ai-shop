-- 自提点没有坐标时，先用它所属小区的坐标兜底。
--
-- 现状：生产上 2 个自提点，**两个都没有坐标**。而端上「导航」入口的显示条件正是
-- `latE6 != null` —— 于是这个功能从上线起对所有人都是隐藏的，
-- 不报错、不占位，只是那一行少一个链接，没人会发现它本该在。
-- 是准备微信 wx.getLocation 申请材料（要证明「提供导航服务」）时才发现的。
--
-- 用小区坐标是**近似**：自提点在小区里或紧邻小区，导航到小区门口已经能用。
-- 精确门牌由商家在 B 端地图选点后覆盖 —— 所以这里只填 NULL 的那些，
-- 不动任何已经有坐标的行。
UPDATE cmt_pickup_point p
JOIN cmt_community c ON c.community_no = p.community_no AND c.deleted = 0
   SET p.lat_e6 = c.lat_e6,
       p.lng_e6 = c.lng_e6,
       p.updated_at = NOW()
 WHERE p.deleted = 0
   AND p.lat_e6 IS NULL
   AND c.lat_e6 IS NOT NULL;
