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
     * @param segmentNo 存下来的人群
     */
    SegmentAudience resolveSegment(String entityNo, String segmentNo);

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
