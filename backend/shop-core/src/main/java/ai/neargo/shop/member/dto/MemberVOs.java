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
     */
    public record MemberQuery(String storeNo, String level, String source, String status,
                              String phone, Long lastOrderBefore, Long lastOrderAfter,
                              Long spentMin, Long spentMax, long page, long size) {
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
                                 List<MemberSourceVO> sources) {
    }
}
