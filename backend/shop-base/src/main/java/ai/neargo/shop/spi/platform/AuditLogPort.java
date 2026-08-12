package ai.neargo.shop.spi.platform;

/**
 * 任意域 → platform：记一条运营操作审计。
 *
 * <p>审计是**横切关注点**：改费率、审商品、封商家、调行业准入，每一件都要留痕，
 * 而它们分散在各个域。让每个域为了写一行审计去依赖整个 platform 域，
 * 是把「记账本」这一件小事变成了模块依赖。
 *
 * <p>刻意不返回任何东西、也不抛异常口径：**审计失败不该让业务失败**。
 * 审核商品成功了却因为写日志报错而回滚，是拿一条记录去换一次真实的业务操作。
 */
public interface AuditLogPort {

    /**
     * @param action 动作码，如 {@code GOODS_AUDIT} / {@code INDUSTRY_ENABLED}
     * @param target 被操作对象的业务键
     * @param detail 人能读的说明（直接展示在运营的审计列表里）
     */
    void record(String action, String target, String detail);

    /**
     * 标记这条操作是否高危，用于运营审计列表的筛选。默认走 {@link #record(String, String, String)}
     * 即非高危 —— 大多数调用点不用改，只有涉及资金/权限/不可逆状态的少数几处需要显式传 true。
     */
    default void record(String action, String target, String detail, boolean critical) {
        record(action, target, detail);
    }

    /**
     * 带结构化前后对比的版本。{@code beforeJson}/{@code afterJson} 是调用方自己序列化好的 JSON 字符串，
     * 没有旧值可比的调用点不要为了凑参数瞎编——直接用不带这两个参数的重载。
     */
    default void record(String action, String target, String detail, boolean critical,
                         String beforeJson, String afterJson) {
        record(action, target, detail, critical);
    }
}
