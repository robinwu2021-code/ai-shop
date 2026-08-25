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
