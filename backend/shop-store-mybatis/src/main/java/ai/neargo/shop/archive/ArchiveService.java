package ai.neargo.shop.archive;

/**
 * 运营端归档（软删除）。<b>一份实现服务四个实体</b>：券、商家、自提点、活动。
 *
 * <p>为什么收成一个服务而不是每个域各写一遍：这四处的逻辑逐字相同 ——
 * 盖个时间戳、列表默认过滤掉、恢复时清空。各写一遍的结果是漂移，
 * 而漂移在这里的表现特别隐蔽：某个域「归档了但列表还显示」，
 * 运营会以为归档没生效，再点一次。
 *
 * <p><b>归档不是删除，也不是停用</b>：
 * <ul>
 *   <li>业务数据全保留，关联记录（订单、领取记录、审计）一条不动</li>
 *   <li>与 {@code status} 正交 —— 暂停的券还在列表里等着被恢复，
 *       归档的券从默认列表消失。一张券完全可以「已暂停 + 已归档」</li>
 *   <li>随时可 {@link #unarchive} 恢复。契约里禁止 {@code delete*}（工程约定 §10.6），
 *       就是因为运营端的「删」几乎总是「不想看见了」，而不是「这条数据错了」</li>
 * </ul>
 */
public interface ArchiveService {

    /**
     * 可归档的实体。<b>取值即 ops-web 的路径段</b>（{@code /ops/coupons/{no}/archive}），
     * 这样端点与实现之间不需要再维护一张映射表。
     */
    enum Kind {
        COUPON, MERCHANT, PICKUP, CAMPAIGN, COMMUNITY, CONTENT_SLOT
    }

    /**
     * 归档。已归档的再归档一次是<b>幂等</b>的，不报错 ——
     * 运营连点两下不该看到一个「已经归档过了」的错误，那对他没有任何意义。
     *
     * @return 归档时间（毫秒）
     * @throws ai.neargo.shop.common.BizException 该实体不存在时 10404
     */
    long archive(Kind kind, String bizNo, String operatorNo);

    /** 恢复。没归档过的恢复一次同样幂等。 */
    void unarchive(Kind kind, String bizNo, String operatorNo);
}
