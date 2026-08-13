-- 定时任务的分布式锁表（ShedLock）。
--
-- 为什么现在加：worker 部署一旦起两个实例，四个 @Scheduled 会各跑一遍。
-- 幂等能保证不加错账，但保证不了不空转 —— 而其中两条会真的出问题：
--   · expireIdleAccounts 清零后转平台收入，两实例同时扫会争同一批账户行
--   · ReconScanJob 每 10 分钟一次，重叠窗口最大
-- 这不是「以后再说」的事：加锁必须在起第二个实例之前，
-- 而那个动作通常是运维在扩容时做的，不会回来问代码准备好没有。
--
-- 表结构由 ShedLock 规定，不能自定义列名（JdbcTemplateLockProvider 硬编码）。
-- 引擎必须是 InnoDB：锁靠的是主键冲突，MyISAM 没有事务语义。
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL COMMENT '锁名 = 任务名，与 @SchedulerLock(name) 一致',
    lock_until TIMESTAMP(3) NOT NULL COMMENT '锁持有到什么时候；到点后其他实例可抢',
    locked_at  TIMESTAMP(3) NOT NULL COMMENT '本次加锁时刻，排查时用来看是谁在跑',
    locked_by  VARCHAR(255) NOT NULL COMMENT '持锁实例标识（默认为主机名）',
    PRIMARY KEY (name)
-- 表尾必须写成一行：解析器（scripts/lib/ddl.mjs）切表的正则是 `\n\)([^;\n]*);`，
-- 尾巴换行就匹配不上，那张表会在 ER 图、血缘、对齐清单里**凭空消失且不报错**
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '定时任务分布式锁（ShedLock 规定的表结构，勿改列名）';
