package ai.neargo.shop.member.service;

/**
 * 会员分层：口径读写与每日重算。
 *
 * <p><b>为什么要每天重算</b>：分层此前只在支付成功时算，一个人不再下单，
 * 他就永远停在最后那一次的分层上 —— 「沉睡」这一档只有在他<b>又来买了一次</b>时才可能被算出来，
 * 而那一刻他恰恰不再沉睡。{@code d90_order_count} 同理只增不减。
 *
 * <p>重算只改分层与近 90 天单数，<b>不触达、不推送</b>：分层变了不等于该给这个人发消息，
 * 那是商家的决定（见 {@code ArchitectureTest} 对本服务依赖面的约束）。
 */
public interface MemberLevelService {

    /** 当前口径。配置读不出或不自洽时返回 {@link LevelPolicy#DEFAULT}，不抛 */
    LevelPolicy policy();

    /** 保存口径。不自洽时抛 {@code BAD_REQUEST}；不触发重算 —— 下一轮任务或手动触发时生效 */
    LevelPolicy savePolicy(LevelPolicy policy, String operatorNo);

    /**
     * 全量重算所有主体的会员分层与近 90 天单数（主体级与门店级两份）。
     * 只写真变了的行：连跑两次，第二次一行都不动。
     */
    RecomputeResult recompute(long now);

    /** 上一次重算的结果；从没跑过时为 null */
    RecomputeResult lastRun();

    /**
     * @param changed       分层或近 90 天单数变了的会员数（主体级）
     * @param newlySleeping 其中这一轮新变成沉睡的人数。第一次上线会很大 —— 那是历史欠账一次性显形
     */
    record RecomputeResult(long at, int scanned, int changed, int newlySleeping, long tookMs) {
    }
}
