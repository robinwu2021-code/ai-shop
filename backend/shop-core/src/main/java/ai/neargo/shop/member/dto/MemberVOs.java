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
                              Long spentMin, Long spentMax, long page, long size,
                              String reachTaskNo, String reachOutcome) {

        /** 某次触达里：下了单的 */
        public static final String REACH_ORDERED = "ORDERED";
        /** 某次触达里：点进店了的 */
        public static final String REACH_OPENED = "OPENED";
        /** 某次触达里：没来的（没点进店） */
        public static final String REACH_NOT_OPENED = "NOT_OPENED";

        /**
         * 不带触达条件的写法。<b>只用于从零新建条件</b>；
         * 由已有条件改写（归一、换标签、去分页）必须走全参构造，否则触达那两格会被悄悄丢掉。
         */
        public MemberQuery(String storeNo, String level, String source, String status,
                           String phone, java.util.List<String> tagNos,
                           Long lastOrderBefore, Long lastOrderAfter,
                           Long spentMin, Long spentMax, long page, long size) {
            this(storeNo, level, source, status, phone, tagNos, lastOrderBefore, lastOrderAfter,
                    spentMin, spentMax, page, size, null, null);
        }

        /**
         * JSON 入口。**分页缺省时按「第 1 页、不限」补上** —— 端上存人群、试算、打标只发筛选条件
         * （{@code {tagNos:[…]}}），不带 page / size；而 Jackson 3 默认 FAIL_ON_NULL_FOR_PRIMITIVES，
         * 两个 long 缺省就整条请求 400，界面上表现为「点了另存为人群没反应」（2026-09-19 真机发现）。
         * 只放宽这一个类型，不改全局配置。
         */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static MemberQuery fromJson(
                @com.fasterxml.jackson.annotation.JsonProperty("storeNo") String storeNo,
                @com.fasterxml.jackson.annotation.JsonProperty("level") String level,
                @com.fasterxml.jackson.annotation.JsonProperty("source") String source,
                @com.fasterxml.jackson.annotation.JsonProperty("status") String status,
                @com.fasterxml.jackson.annotation.JsonProperty("phone") String phone,
                @com.fasterxml.jackson.annotation.JsonProperty("tagNos") java.util.List<String> tagNos,
                @com.fasterxml.jackson.annotation.JsonProperty("lastOrderBefore") Long lastOrderBefore,
                @com.fasterxml.jackson.annotation.JsonProperty("lastOrderAfter") Long lastOrderAfter,
                @com.fasterxml.jackson.annotation.JsonProperty("spentMin") Long spentMin,
                @com.fasterxml.jackson.annotation.JsonProperty("spentMax") Long spentMax,
                @com.fasterxml.jackson.annotation.JsonProperty("page") Long page,
                @com.fasterxml.jackson.annotation.JsonProperty("size") Long size,
                @com.fasterxml.jackson.annotation.JsonProperty("reachTaskNo") String reachTaskNo,
                @com.fasterxml.jackson.annotation.JsonProperty("reachOutcome") String reachOutcome) {
            return new MemberQuery(storeNo, level, source, status, phone, tagNos, lastOrderBefore, lastOrderAfter,
                    spentMin, spentMax, page == null ? 1 : page, size == null ? 0 : size, reachTaskNo, reachOutcome);
        }

        /** 人群条件里不带分页 —— 试算与解析都是全量 */
        public MemberQuery unpaged() {
            return new MemberQuery(storeNo, level, source, status, phone, tagNos,
                    lastOrderBefore, lastOrderAfter, spentMin, spentMax, 1, 0, reachTaskNo, reachOutcome);
        }
    }

    /**
     * @param phoneTail 后四位。没绑手机号（不该出现在会员里）时为空
     * @param level     按主体或按门店的分层，取哪一个由主体的经营口径决定
     * @param tagNames  他身上的商家标签名（名单卡片第二行，原型 m01）。只在名单接口里填，其余为空列表
     */
    public record MemberVO(String memberNo, String personNo, String phoneTail, String status,
                           String source, String level, String firstStoreNo,
                           Integer orderCount, Long totalSpentMinor,
                           Integer d90OrderCount, Long lastOrderAt, Integer daysSinceLast,
                           boolean reachOptOut, String remark, long joinedAt,
                           List<String> tagNames) {

        /** 换上标签名（名单页按页批量取，不逐行查） */
        public MemberVO withTags(List<String> names) {
            return new MemberVO(memberNo, personNo, phoneTail, status, source, level, firstStoreNo,
                    orderCount, totalSpentMinor, d90OrderCount, lastOrderAt, daysSinceLast,
                    reachOptOut, remark, joinedAt, names);
        }
    }

    /**
     * @param unlinkedBuyers 未绑手机号、因此没计进会员的买家数。
     *                       <b>要显示在页面顶部</b> —— 商家一定会拿订单数与会员数对，
     *                       对不上时他的第一反应是数据丢了
     */
    public record MemberStatsVO(int newCount, int regularCount, int loyalCount, int sleepingCount,
                                int reachable, int newThisMonth, int unlinkedBuyers,
                                Long levelComputedAt) {
    }

    public record MemberStoreVO(String storeNo, Integer orderCount, Long totalSpentMinor,
                                Long lastOrderAt, boolean isFirstStore) {
    }

    public record MemberSourceVO(String sourceType, String storeNo, String linkNo,
                                 String inviterUserNo, String inviterRole, String operatorNo,
                                 String activityNo, boolean isFirst, long occurredAt) {
    }

    /**
     * @param lastReach 最近一次触达（原型 m04）。没发过为空。
     *                  商家打电话前能先看到上周已经发过一次唤回、而且他来了
     */
    public record MemberDetailVO(MemberVO member, List<MemberStoreVO> stores,
                                 List<MemberSourceVO> sources, List<TagVO> tags, LastReach lastReach) {
    }

    /** 一个人身上最近的一次触达：场景、时刻、之后来没来、下没下单 */
    public record LastReach(String taskNo, String scene, long sentAt, Long openedAt, Long orderedAt) {
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

    /**
     * 运营侧看到的一条会员（P8）。
     *
     * @param phoneTail <b>只有后四位</b>。运营端不给完整号 ——
     *                  给了就等于全平台会员库对内公开
     * @param entityName 归属商家。只给主体号的话，运营端要自己去别处翻
     */
    public record OpsMemberVO(String memberNo, String personNo, String phoneTail,
                              String entityNo, String entityName, String status,
                              String source, String level, int orderCount,
                              long totalSpentMinor, boolean reachOptOut, long joinedAt) {
    }

    /**
     * 人档详情（运营侧）。
     *
     * @param userNo     绑没绑账号。空 = 只是个被商家录进来的号
     * @param memberships 他是哪几家店的会员 —— <b>这正是人档存在的理由</b>：
     *                    一份人档串起几家商家的会员关系
     * @param merges     合并历史。<b>常年应该是空的</b>：会员必须有手机号之后，
     *                   只剩换号撞档与人工纠错两种，不空就说明别处错了
     */
    public record OpsPersonVO(String personNo, String phoneTail, String userNo,
                              List<OpsMemberVO> memberships, List<String> merges) {
    }

    /**
     * 触达健康度（运营侧）。
     *
     * @param optOutRate 退订率。<b>这是这条线唯一的健康指标</b> ——
     *                   发得多不算成绩，发到有人关掉才是问题
     * @param tagCount     标签<b>个数</b>、人群<b>个数</b>、触达<b>次数</b>（AC-15）。
     *                     <b>只有计数，没有标签名与人群条件</b>：运营找商家谈话只需要知道
     *                     「他在反复给同一批人发」，不需要知道他给谁打了什么标签
     * @param skipRate     跳过率 = 被频次闸等拦下的 / 命中的。高＝在反复给同一批人发
     */
    public record ReachStatVO(String entityNo, String entityName, int sent, int members,
                              int optOut, double optOutRate,
                              int tagCount, int segmentCount, int tasks, int skipped, double skipRate) {
    }

    public record MemberSettingVO(String memberScope, boolean autoJoinOnOrder,
                                  int sleepDays, int loyalD90Orders, int regularD90Orders,
                                  Long levelComputedAt) {
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

    /**
     * 批量打标的试算 / 结果。
     *
     * @param matched        圈到的人（属于本店的）
     * @param alreadyInState 已经是目标状态的（打：本来就有；去：本来就没有）—— 不重复计
     * @param willChange     实际会改的人数。确认框上写的就是它
     * @param skippedFull    标签已满（每人上限）而跳过的
     */
    public record BatchTagVO(int matched, int alreadyInState, int willChange, int skippedFull,
                             boolean applied) {
    }

    /**
     * 选人面板的试算（原型 m12 / m13）。
     *
     * @param matched   命中多少人；含「非本店会员」时为 null（数不出来）
     * @param reachable 其中收得到的；活动场景（forActivity）不算，为 null —— 活动不推送
     * @param skips     收不到的按原因分档，沿用发消息的四档加 BLOCKED
     */
    public record AudiencePreviewVO(Integer matched, Integer reachable,
                                    List<ai.neargo.shop.spi.member.MemberQueryPort.Skip> skips) {
    }

    /**
     * 一个标签用在哪（原型 m08）。停用 / 合并前先让商家看见后果。
     *
     * @param newThisMonth 本月新打上的人数
     * @param activities   引用它的未结束活动
     * @param segments     条件里含它的人群
     */
    public record TagUsageVO(TagVO tag, int newThisMonth,
                             List<ai.neargo.shop.spi.marketing.AudienceRefPort.AudienceRef> activities,
                             List<SegmentVO> segments) {
    }

    /**
     * 人群详情（原型 m11）：条件 + 此刻人数 + 用在哪。
     *
     * @param matched      此刻命中（当场算，不是 lastCount）
     * @param reachable    其中收得到消息的
     * @param activities   引用它的未结束活动。进行中的按发布那一刻的条件生效 —— 改这里的条件不影响它们
     * @param couponIssues 按它发过的券（最近 20 批）
     */
    public record SegmentDetailVO(SegmentVO segment, int matched, int reachable,
                                  List<ai.neargo.shop.spi.marketing.AudienceRefPort.AudienceRef> activities,
                                  List<ai.neargo.shop.spi.marketing.AudienceRefPort.AudienceRef> couponIssues) {
    }
}
