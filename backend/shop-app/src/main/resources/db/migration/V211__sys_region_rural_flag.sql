-- 村/社区（sys_region 第五级）是不是「村委会」类型（rural=1）——决定经营范围选择器
-- 要不要再往下钻一层：村委会到这一级就是终点；社区/居委会底下还要再挑具体小区。
--
-- 为什么不用名字后缀现算，改成落库存一列：民政部给这一级的官方名称本身就带着
-- 「村民委员会」还是「居民委员会」这个法定类型（不是猜的，是这一级行政单位的
-- 法定称谓），值得只读一次、存成明确的列，而不是让"判断是不是村委会"这段
-- 正则散落在好几处代码里各抄一份——这次因为这样已经踩过三次同一个漏后缀的坑
-- （PlaceNames.norm 漏了「村委会」、client 端 looksLikeEstate 的 TAIL 正则太贪婪）。
--
-- 只在导入这一刻用一次名字后缀分类，之后系统只读这一列，不再解析名字。
ALTER TABLE sys_region
    ADD COLUMN rural TINYINT(1) NOT NULL DEFAULT 0 COMMENT '第五级专用：1=村委会（到此为止，不再往下钻），0=社区/居委会（可继续钻到具体小区）或非第五级';

UPDATE sys_region
SET rural = 1
WHERE level = 'VILLAGE' AND name REGEXP '(村委会|村民委员会)$';
