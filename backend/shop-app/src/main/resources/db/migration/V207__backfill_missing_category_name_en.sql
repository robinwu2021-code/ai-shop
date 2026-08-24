-- 补三个类目的英文名：熟食卤味 / 医药健康 / 维修安装。
--
-- 这三个在种子里就漏了 name_en。后果不是报错，是 **C 端英文界面静默回落中文** ——
-- 英文用户看到的类目列表里夹着三个汉字词，而没有任何一处会提示这件事出了岔。
-- 运营端类目表上那枚「缺译」角标是唯一看得见的信号，本轮就是顺着它找到的。
--
-- 只补空的：`name_en IS NULL OR name_en = ''`。类目名在运营端可编辑，
-- 无条件 UPDATE 会把运营已经改过的译法冲掉 —— 而那种覆盖不留痕迹，
-- 下次谁也想不起来是一条迁移干的。
--
-- 译法跟已有的那批对齐（Vegetables / Personal Care / Housekeeping 那种
-- 直白的类目词，不用行业黑话）：
--   熟食卤味 → Deli                **沿用本地库里已有的那个**，不是我另起的说法。
--                                  同一个类目在两个环境叫两个名字，比缺译更难查 ——
--                                  缺译至少有角标，两套译法则要有人同时看过两边才发现。
--   医药健康 → Health & Medicine   不用 Pharmacy：这一类还含保健品，不只是药
--   维修安装 → Repair & Install    与 CAT310 Housekeeping 一样落在服务动作上

UPDATE prd_category SET name_en = 'Deli', updated_at = NOW()
 WHERE category_no = 'CAT140' AND deleted = 0 AND (name_en IS NULL OR name_en = '');

UPDATE prd_category SET name_en = 'Health & Medicine', updated_at = NOW()
 WHERE category_no = 'CAT240' AND deleted = 0 AND (name_en IS NULL OR name_en = '');

UPDATE prd_category SET name_en = 'Repair & Install', updated_at = NOW()
 WHERE category_no = 'CAT320' AND deleted = 0 AND (name_en IS NULL OR name_en = '');
