package ai.neargo.shop.member.service;

import ai.neargo.shop.member.entity.MbrMember;

/**
 * 会员分层口径：多少天没下单算沉睡、近 90 天几单算熟客 / 常客。
 *
 * <p>整条口径存成 {@code sys_setting} 里的一个 JSON（键 {@link #KEY}），写法同
 * {@code inventory.policy}。下单即时算与每日重算读的是<b>同一份</b> ——
 * 两处各有一套阈值的话，同一个人白天是熟客、凌晨变常客，商家看到的是数字在跳。
 *
 * @param sleepDays        超过这么多天没下单算沉睡（严格大于）
 * @param loyalD90Orders   近 90 天至少这么多单算熟客
 * @param regularD90Orders 近 90 天至少这么多单算常客；再少就是新客
 */
public record LevelPolicy(int sleepDays, int loyalD90Orders, int regularD90Orders) {

    public static final String KEY = "member.level.policy";

    /** 与此前写死在 {@code MemberServiceImpl#levelOf} 里的 60 / 6 / 2 逐字一致：不配置时行为不变 */
    public static final LevelPolicy DEFAULT = new LevelPolicy(60, 6, 2);

    private static final long DAY = 86_400_000L;
    private static final int SLEEP_DAYS_MIN = 7;
    private static final int SLEEP_DAYS_MAX = 365;
    private static final int ORDERS_MAX = 99;

    /**
     * <b>先判沉睡再判活跃</b> —— 一个曾经的熟客三个月没来，商家要看到的是「沉睡」，不是「熟客」。
     * 从没下过单的人（{@code lastOrderAt == null}）不算沉睡，他是新客。
     */
    public String levelOf(Integer d90Orders, Long lastOrderAt, long now) {
        if (lastOrderAt != null && (now - lastOrderAt) / DAY > sleepDays) {
            return MbrMember.LEVEL_SLEEPING;
        }
        int n = d90Orders == null ? 0 : d90Orders;
        if (n >= loyalD90Orders) {
            return MbrMember.LEVEL_LOYAL;
        }
        return n >= regularD90Orders ? MbrMember.LEVEL_REGULAR : MbrMember.LEVEL_NEW;
    }

    /**
     * 口径是否自洽。常客门槛必须低于熟客门槛 —— 反过来的话熟客那一档永远先命中，
     * 「常客」这个层会整片消失，而页面上只会看到一个 0。
     */
    public boolean valid() {
        return sleepDays >= SLEEP_DAYS_MIN && sleepDays <= SLEEP_DAYS_MAX
                && regularD90Orders >= 1 && loyalD90Orders <= ORDERS_MAX
                && regularD90Orders < loyalD90Orders;
    }
}
