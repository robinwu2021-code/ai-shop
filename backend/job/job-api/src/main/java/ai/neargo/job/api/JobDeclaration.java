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
        /*
         * **这两个数字是对同一个问题的两个答案**：这个任务最长能跑多久。
         *
         * 差得太远就说明至少有一个是随手填的。2026-08-28 实测：11 个任务里 6 个是
         * timeout=60 / lock=1800（差 30 倍）—— 因为 daily() 里写死了这一对，
         * 而没人为具体任务想过。后果是跑过 60 秒的任务永远记成 TIMEOUT，
         * 而它其实成功了；TIMEOUT 又不计入连续失败，所以也不会有人被提醒。
         *
         * 锁要比调用活得久（超时之后业务侧还在跑），所以 lock ≥ timeout；
         * 但超过 4 倍就不是「留余量」而是两个数字在说不同的事。
         */
        if (lockAtMostSec < timeoutSec) {
            throw new IllegalArgumentException(
                    "lockAtMostSec 不能小于 timeoutSec —— 锁会在调用还没结束时释放，"
                            + "另一个实例就能同时跑同一个任务：" + handlerName);
        }
        if (lockAtMostSec > (long) timeoutSec * MAX_LOCK_RATIO) {
            throw new IllegalArgumentException(
                    ("%s 的 timeoutSec=%d 与 lockAtMostSec=%d 差了 %d 倍 —— "
                            + "这两个数字都在回答「这任务最长跑多久」，差这么多说明至少有一个没想过。"
                            + "把 timeout 提到接近 lock，或者把 lock 降到接近 timeout。")
                            .formatted(handlerName, timeoutSec, lockAtMostSec,
                                    lockAtMostSec / timeoutSec));
        }
    }

    /** 锁最多比超时长几倍。**4 是「留余量」与「两个数字在说不同的事」之间的分界**。 */
    private static final int MAX_LOCK_RATIO = 4;

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    /**
     * 常见形状：每天跑一次的维护任务。
     *
     * <p><b>10 分钟超时 / 15 分钟持锁</b>。原先是 60 秒 / 30 分钟 —— 那一对
     * 是 2026-08-28 复核时发现的问题源头：11 个任务里 6 个用了它，
     * 于是「这任务最长跑多久」这个问题在生产上有两个相差 30 倍的答案。
     *
     * <p>夜间维护任务（积分转正、资质扫描、媒体扫描）在数据量长起来之后
     * 跑几分钟是正常的，60 秒会把它们全部记成 TIMEOUT。
     */
    public static JobDeclaration daily(String handlerName, String displayName, String description,
                                       String ownerModule, String cron) {
        return new JobDeclaration(handlerName, displayName, description, ownerModule,
                cron, true, 600, 900, true, true);
    }
}
