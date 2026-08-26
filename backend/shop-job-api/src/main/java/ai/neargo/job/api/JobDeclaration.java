package ai.neargo.job.api;

/**
 * 代码侧对一个任务的**初始**声明。业务系统交出来，worker 首次见到时写进 {@code job_definition}。
 *
 * <p><b>「初始」两个字是这个类的全部要点。</b>其中 {@link #defaultCron} 与 {@link #enabled}
 * 只在**第一次**写库时生效；之后这两列归运营，代码永远不再覆盖。
 * 否则运营在页面上改的 cron 会在下次发版时被悄悄冲掉 ——
 * 没有报错、没有日志，只是某天起任务又按老点跑了。
 *
 * <p>反过来，{@link #displayName} / {@link #description} / {@link #ownerModule}
 * 是「只有代码知道」的，每次启动都覆盖成最新的。
 *
 * @param handlerName    对应 {@link JobHandler#name()}
 * @param displayName    给运营看的中文名，如「套餐到期扫描」。**不要写 plan-expiry**
 * @param description    这个任务干什么，页面直接显示
 * @param ownerModule    出问题时去哪个模块找，如 shop-settle
 * @param defaultCron    6 段 Spring cron。**仅首次入库生效**
 * @param enabled        首次入库时开不开。**仅首次生效**
 * @param timeoutSec     worker 等多久。超时记 TIMEOUT（结果未知，不等于失败）
 * @param lockAtMostSec  业务侧持锁上限。进程被杀后锁最多卡这么久
 * @param manualTrigger  页面上显不显示「立即执行」
 * @param logEveryRun    false = 只在状态变化或失败时落日志（高频任务用，否则日志表会长成最大的表）
 */
public record JobDeclaration(
        String handlerName,
        String displayName,
        String description,
        String ownerModule,
        String defaultCron,
        boolean enabled,
        int timeoutSec,
        int lockAtMostSec,
        boolean manualTrigger,
        boolean logEveryRun) {

    public JobDeclaration {
        require(handlerName, "handlerName");
        require(displayName, "displayName");
        require(defaultCron, "defaultCron");
        if (timeoutSec <= 0) {
            throw new IllegalArgumentException("timeoutSec 必须为正：" + handlerName);
        }
        if (lockAtMostSec <= 0) {
            throw new IllegalArgumentException("lockAtMostSec 必须为正：" + handlerName);
        }
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    /** 常见形状：每天跑一次、60 秒超时、持锁 30 分钟、可手动触发、每轮都记日志。 */
    public static JobDeclaration daily(String handlerName, String displayName, String description,
                                       String ownerModule, String cron) {
        return new JobDeclaration(handlerName, displayName, description, ownerModule,
                cron, true, 60, 1800, true, true);
    }
}
