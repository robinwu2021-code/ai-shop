package ai.neargo.shop.job;

import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 定时任务的统一外壳：计时、记录、**兜住异常**。
 *
 * <p><b>为什么要统一</b>：`@Scheduled` 方法抛出异常时，Spring 只打一行 ERROR，
 * 而下一轮照常跑 —— 看着像没事，实际是每隔一个周期失败一次而没人发现。
 * 每个任务各写一遍 try/catch 的结果是：写了的兜住了，忘了的静默失败，
 * 而**忘了的那个恰恰是最新加的那个**。
 *
 * <p><b>它同时回答一个更基本的问题：这个任务到底跑没跑过。</b>
 * 这一轮撞到过 `OutboxDispatcher` 写好了但调度任务从来没被写出来 ——
 * 全站站内信发不出去而测试全绿。有了运行记录，
 * 「从来没有一条 outbox 的记录」第一天就会露出来。
 *
 * <p>用法：
 * <pre>
 * &#64;Scheduled(cron = "...")
 * &#64;SchedulerLock(name = "outbox-dispatch", ...)
 * public void dispatch() {
 *     jobs.run("outbox-dispatch", () -> {
 *         int n = dispatcher.dispatchPending();
 *         return n == 0 ? null : "投出 " + n + " 条";   // null = 这轮没做事
 *     });
 * }
 * </pre>
 */
@Component
public class JobSupport {

    private static final Logger log = LoggerFactory.getLogger(JobSupport.class);

    /** 连续失败到这个数就升级成 error 日志 —— 单次失败多半是抖动，连续失败才要人看 */
    private static final int ALERT_AFTER = 3;

    /** {@code detail} 列宽 255，超了直接截 —— 一条日志不该让整轮记录写不进去 */
    private static final int DETAIL_MAX = 255;

    public interface JobRunMapper extends BaseMapper<SysJobRun> {
    }

    private final JobRunMapper mapper;

    public JobSupport(JobRunMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 跑一轮，并把结果记下来。
     *
     * @param jobName 与 {@code @SchedulerLock} 的 name 一致 —— 两处不一致时，
     *                运行记录查不到锁、锁也对不上记录，排查要在两套名字之间猜
     * @param body    返回「这一轮做了什么」的描述；**返回 null 表示这轮什么也没做**
     *                （没有待处理的数据是常态，不值得记一句「成功」）
     */
    public void run(String jobName, Supplier<String> body) {
        long started = System.nanoTime();
        LocalDateTime at = LocalDateTime.now();
        try {
            String detail = body.get();
            record(jobName, at, ms(started), SysJobRun.OK, detail, null);
            if (detail != null) {
                log.info("[job] {} —— {}", jobName, detail);
            }
        } catch (RuntimeException e) {
            /*
             * **吞掉异常**：这是唯一该吞的地方。抛出去的话 Spring 只打一行 ERROR，
             * 而这里能记进表、能计连续失败、能在连续失败时升级日志级别。
             * 吞了不等于忽略 —— 下面每一条路径都留了痕。
             */
            int fails = record(jobName, at, ms(started), SysJobRun.FAILED, null, e.toString());
            if (fails >= ALERT_AFTER) {
                log.error("[job] {} 已连续失败 {} 次 —— 这不再是抖动，去看", jobName, fails, e);
            } else {
                log.warn("[job] {} 失败（第 {} 次）", jobName, fails, e);
            }
        }
    }

    private static long ms(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 写记录。**独立事务**：任务体自己的事务失败要回滚，
     * 而「它失败过」这件事不能跟着回滚掉 —— 那正是最需要留下的一条。
     *
     * @return 连续失败次数（成功时为 0）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected int record(String jobName, LocalDateTime at, long durationMs,
                         String status, String detail, String error) {
        try {
            return DataScopeContext.executeWithoutScope(() -> {
                SysJobRun row = mapper.selectOne(Wrappers.<SysJobRun>lambdaQuery()
                        .eq(SysJobRun::getJobName, jobName).last("limit 1"));
                boolean failed = SysJobRun.FAILED.equals(status);
                if (row == null) {
                    row = new SysJobRun();
                    row.setJobName(jobName);
                    row.setRunCount(0L);
                    row.setConsecutiveFailures(0);
                }
                row.setLastRunAt(at);
                row.setDurationMs(durationMs);
                row.setStatus(status);
                row.setDetail(truncate(detail, DETAIL_MAX));
                row.setError(truncate(error, 512));
                // 成功即清零：按「连续」计数而不是累计，否则一个跑了半年的任务会攒出吓人的数字
                row.setConsecutiveFailures(failed ? row.getConsecutiveFailures() + 1 : 0);
                row.setRunCount(row.getRunCount() + 1);
                if (row.getId() == null) {
                    mapper.insert(row);
                } else {
                    mapper.updateById(row);
                }
                return row.getConsecutiveFailures();
            });
        } catch (RuntimeException e) {
            // 记录本身失败**绝不能影响任务** —— 记录是辅助，任务是主线
            log.error("[job] 写运行记录失败 job={}", jobName, e);
            return 0;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
