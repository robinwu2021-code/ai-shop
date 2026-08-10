-- 社区网格与覆盖围栏（P-2.1.2 / P-2.1.3）。
--
-- 运营端早就按这两个字段做筛选与排期（ops-web 的 Community 里一直声明着），
-- 而库里没有 —— 端上拿到的是 undefined，围栏半径显示成空、网格筛选筛不出任何东西。
ALTER TABLE cmt_community
    ADD COLUMN grid VARCHAR(64) DEFAULT NULL
        COMMENT '网格：城市与社区之间的运营划分单位，BD 按网格分片包干' AFTER city_code;

-- 覆盖围栏半径，米。默认 1000：楼下自提的合理步行范围，
-- 而不是 0 —— 0 会让「这个社区覆盖不到任何地址」，且看起来像「还没配」
ALTER TABLE cmt_community
    ADD COLUMN fence_radius INT(11) NOT NULL DEFAULT 1000
        COMMENT '覆盖围栏半径（米）。C 端按它判断地址是否落在本社区内' AFTER grid;
