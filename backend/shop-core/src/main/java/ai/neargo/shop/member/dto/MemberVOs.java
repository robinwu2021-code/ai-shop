package ai.neargo.shop.member.dto;

import java.util.List;

/**
 * 会员域对外的读模型。
 *
 * <p><b>手机号一律只给后四位</b>：需要完整号的只有平台申诉处置，那条路要二次确认与审计日志，
 * 不从这里走。
 */
public final class MemberVOs {

    private MemberVOs() {
    }

    /**
     * 会员列表的筛选条件。
     *
     * @param storeNo 空 = 按主体口径；主体开了「按门店经营」时**必填**
     * @param phone   <b>完整手机号才匹配</b>。前缀模糊查询会把会员库变成通讯录
     * @param tagNos  <b>取交集</b>：选两个标签是「都要满足」，不是「任一」。
     *                并集会算出比单选任何一个都大的人群 —— 商家点第二个标签是想收窄，
     *                结果人数反而涨了，没人看得懂。界面上写「同时含以下标签」
     */
    public record MemberQuery(String storeNo, String level, String source, String status,
                              String phone, java.util.List<String> tagNos,
                              Long lastOrderBefore, Long lastOrderAfter,
                              Long spentMin, Long spentMax, long page, long size) {

        /** 人群条件里不带分页 —— 试算与解析都是全量 */
        public MemberQuery unpaged() {
            return new MemberQuery(storeNo, level, source, status, phone, tagNos,
                    lastOrderBefore, lastOrderAfter, spentMin, spentMax, 1, 0);
        }
    }

    /**
     * @param phoneTail 后四位。没绑手机号（不该出现在会员里）时为空
     * @param level     按主体或按门店的分层，取哪一个由主体的经营口径决定
     */
    public record MemberVO(String memberNo, String personNo, String phoneTail, String status,
                           String source, String level, String firstStoreNo,
                           Integer orderCount, Long totalSpentMinor,
                           Integer d90OrderCount, Long lastOrderAt, Integer daysSinceLast,
                           boolean reachOptOut, String remark, long joinedAt) {
    }

    /**
     * @param unlinkedBuyers 未绑手机号、因此没计进会员的买家数。
     *                       <b>要显示在页面顶部</b> —— 商家一定会拿订单数与会员数对，
     *                       对不上时他的第一反应是数据丢了
     */
    public record MemberStatsVO(int newCount, int regularCount, int loyalCount, int sleepingCount,
                                int reachable, int newThisMonth, int unlinkedBuyers) {
    }

    public record MemberStoreVO(String storeNo, Integer orderCount, Long totalSpentMinor,
                                Long lastOrderAt, boolean isFirstStore) {
    }

    public record MemberSourceVO(String sourceType, String storeNo, String linkNo,
                                 String inviterUserNo, String inviterRole, String operatorNo,
                                 String activityNo, boolean isFirst, long occurredAt) {
    }

    public record MemberDetailVO(MemberVO member, List<MemberStoreVO> stores,
                                 List<MemberSourceVO> sources, List<TagVO> tags) {
    }

    /**
     * 人群：一组条件 + 上次算出的人数。
     *
     * @param lastCount 上次算的人数，**只是展示** —— 发券与触达前会当场重算。
     *                  界面上要把「上次算于 X 时」写出来，否则商家会拿它当此刻的人数
     */
    public record SegmentVO(String segmentNo, String name, String scopeStoreNo,
                            MemberQuery rule, int lastCount, Long countedAt) {
    }

    /**
     * 会员经营口径。
     *
     * @param memberScope ENTITY 按主体（默认）/ STORE 按门店。
     *                    <b>只改展示与分层口径，不改存储</b> —— 两级指标一直都在算，
     *                    所以随时可切、切回来也不丢
     */
    /**
     * 人群试算结果。
     *
     * @param count     条件命中多少人
     * @param reachable 其中<b>能真正收到东西</b>的有多少（排除线索会员与退订的人）。
     *                  只报 count 的话，商家在人群页看到 120、发放页发出 96，
     *                  他会以为发漏了 —— 而实际上那 24 个人从一开始就进不了受众
     */
    public record SegmentPreviewVO(int count, int reachable) {
    }

    /**
     * 「我是这家店的会员」（C 端）。
     *
     * @param reachOptOut 我关掉了这家店的消息没有。<b>只有本人能改</b>
     * @param joinedAt    什么时候成为会员的 —— 顾客问「我怎么成了会员」时的答案
     */
    public record MyMembershipVO(String entityNo, String entityName, String level,
                                 int orderCount, long totalSpentMinor,
                                 boolean reachOptOut, long joinedAt) {
    }

    public record MemberSettingVO(String memberScope, boolean autoJoinOnOrder) {
    }

    /**
     * @param tagType SYS 系统算的（只读）/ MCH 商家的
     * @param count   打了多少人。COUNT 出来的 —— 不存冗余列，标签总量只有几十个
     */
    public record TagVO(String tagNo, String name, String tagType, String status, int count) {
    }

    /**
     * 合并的影响面。<b>先给商家看这三个数，再让他按</b> —— 合并不可逆。
     *
     * @param bothTagged           两个标签都有的人。他们合并后只保留一条
     * @param referencedActivities 引用了源标签的活动数。它们会被一起改写
     */
    public record MergePreviewVO(int affectedMembers, int bothTagged, int referencedActivities,
                                 boolean applied) {
    }
}
