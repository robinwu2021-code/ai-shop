package ai.neargo.shop.spi.member;

import java.util.List;

/**
 * promotion → member：<b>发给哪一群人</b>。
 *
 * <p>方向是单向的：营销问会员「这个人群此刻是谁」，会员不问营销。
 *
 * <p><b>为什么不让营销自己去筛</b>：同一群人在发券、活动受众、触达三处
 * 各筛一遍，就会算出三个数，而商家分不清哪个对。筛人只有会员域一处实现
 * （{@code MemberService.match}），这里只是把它借出去。
 */
public interface MemberQueryPort {

    /**
     * 人群此刻命中谁。<b>当场算，不吃缓存</b> —— 名单每天都在变。
     *
     * @param segmentNo 存下来的人群；或预设人群 {@code @ALL} / {@code @NEW} / {@code @REGULAR} / {@code @LOYAL} / {@code @SLEEPING}（按分层现筛，发券页 s18 的前四行）
     */
    SegmentAudience resolveSegment(String entityNo, String segmentNo);

    /**
     * 一组受众项此刻命中谁（活动 / 发券 / 发消息共用的那一块「发给谁」）。
     *
     * <p><b>多项之间取或</b>：「沉睡的 + 爱囤货的都给」是一次发给两拨人，重叠的只算一次。
     * 要「既沉睡又爱囤货」，先在筛选里存成人群，再选这个人群 —— 筛选条件内部是取且。
     *
     * @param items 不能为空（发券 / 发消息必须说清发给谁）；含 {@link AudienceItem#NON_MEMBER} 时只能有它一项
     * @param scene 发消息时给场景（NOTICE / WAKEUP / COUPON），频次闸按它判；发券与活动给 null
     * @return 含 NON_MEMBER 时 {@code countable=false}：「所有不是本店会员的人」数不出来
     */
    AudienceResolution resolve(String entityNo, List<AudienceItem> items, String scene);

    /**
     * 这个买家是否满足一份<b>条件快照</b>（活动发布那一刻抄下来的人群条件）。
     * 与 {@link #judge} 分开：快照是「当时的人群」，judge 里的 segmentNos 是「现在的人群」。
     */
    boolean matchesRule(String entityNo, String userNo, String ruleSnapshot);

    /**
     * 把人群此刻的条件抄一份，给活动存成快照（AC-9：进行中的活动按发布时的条件生效）。
     * 人群不存在时抛 {@code MEMBER_SEGMENT_NOT_FOUND}。
     */
    String segmentSnapshot(String entityNo, String segmentNo);

    /**
     * 一个受众项。
     *
     * @param type  {@link #ALL} 全部会员 · {@link #LEVEL} 分层（NEW/REGULAR/LOYAL/SLEEPING）·
     *              {@link #TAG} 标签号 · {@link #SEGMENT} 人群号 · {@link #SOURCE} 首次来源 ·
     *              {@link #NON_MEMBER} 非本店会员（只在活动里）
     * @param value 存号不存文本 —— 标签改名不该动到这里
     */
    record AudienceItem(String type, String value) {
        public static final String ALL = "ALL";
        public static final String LEVEL = "LEVEL";
        public static final String TAG = "TAG";
        public static final String SEGMENT = "SEGMENT";
        public static final String SOURCE = "SOURCE";
        public static final String NON_MEMBER = "NON_MEMBER";
    }

    /**
     * @param matched   命中多少人（含发不出去的）；{@code countable=false} 时为 0 且不该展示
     * @param reachable 其中能真正收到东西的
     * @param skips     发不出去的按原因分档：LEAD 手录未认领 · BLOCKED 被商家拉黑 · OPT_OUT 关了本店消息 ·
     *                  NO_ACCOUNT 还没注册 · TOO_SOON 频次闸内（只在给了 scene 时判）
     */
    record AudienceResolution(int matched, List<Audience> reachable, List<Skip> skips,
                              boolean countable) {
    }

    /** 一档跳过原因与人数 */
    record Skip(String reason, int count) {
    }

    /**
     * 这个买家在这家主体的会员画像 —— <b>受众判断一次取回，不要逐条问</b>。
     *
     * <p>算价是在下单路径上，每多一次跨域调用都乘以订单量。此前的教训是
     * 「一个活动一次查询」：三个活动就是三趟，而它们问的是同一个人。
     *
     * @return 他还不是这家店的会员时，{@code member} 为 false，其余字段为空 ——
     *         <b>不是抛异常</b>：拉新活动要的正是这种人
     */
    MemberSnapshot judge(String entityNo, String userNo);

    /**
     * @param member     是不是这家主体的会员（{@code ACTIVE}，线索不算）
     * @param level      NEW / REGULAR / LOYAL / SLEEPING
     * @param source     首次来源
     * @param tagNos     他身上的标签号
     * @param segmentNos 他此刻命中的人群号。<b>当场算</b> —— 人群存的是条件不是名单
     */
    record MemberSnapshot(boolean member, String level, String source,
                          java.util.Set<String> tagNos, java.util.Set<String> segmentNos) {

        public static MemberSnapshot notMember() {
            return new MemberSnapshot(false, null, null, java.util.Set.of(), java.util.Set.of());
        }
    }

    /**
     * @param matched   条件命中多少人（含发不出去的）
     * @param reachable 其中<b>能真正收到东西</b>的。两个数都给，是因为
     *                  「发了 25 张、跳过 12 个」这句话必须说得出来 ——
     *                  只给可触达的话，商家会以为人群本来就只有 25 个人
     */
    record SegmentAudience(int matched, List<Audience> reachable) {
    }

    /** @param userNo 平台账号。线索会员没有账号，不会出现在这里 */
    record Audience(String memberNo, String userNo) {
    }
}
