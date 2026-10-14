package ai.neargo.shop.spi.marketing;

import ai.neargo.shop.event.DomainEvent;

import java.util.List;

/** marketing 域对外发布的事件。 */
public final class MarketingEvents {

    private MarketingEvents() {
    }

    /**
     * 团有结果了 —— 成了，或者到期没成。
     *
     * <p><b>拼团是唯一「开团人必须离开去等结果」的玩法，而它此前一条通知都没有</b>
     * （2026-09-29 查证：成团那条原子 UPDATE 与 {@code GroupExpireJob} 都不发事件，
     * 连站内信都没有）。开团的人把链接转出去之后就退出小程序了，
     * 团成没成、钱退没退，他只能自己想起来回去看。
     *
     * <p><b>成团与失败用同一条事件、靠 {@code status} 分</b>：
     * 发布点是同一处（团的状态机），分成两个 record 会让发布方多一个分支，
     * 而那个分支迟早会漏掉一边 —— 消费端要分文案，那是消费端的事。
     *
     * @param status   {@code FORMED} / {@code FAILED}
     * @param userNos  这个团里所有人（开团人 + 参团人）。<b>团规模有界</b>
     *                 （{@code min_count} 通常个位数），所以名单直接放事件里，
     *                 不必为此新开一条跨域查询
     */
    public record GroupSettled(String groupNo, String status, String title, List<String> userNos)
            implements DomainEvent {

        /** 成团。 */
        public static final String FORMED = "FORMED";
        /** 到期未成团。 */
        public static final String FAILED = "FAILED";

        @Override
        public String aggregateType() {
            return "GROUP";
        }

        @Override
        public String aggregateId() {
            return groupNo;
        }

        /**
         * <p><b>按结果给事件类型</b>，这样两种结果可以在场景×通道表里分别开关 ——
         * 「成团」是喜讯、「未成团」关系到退款，运营可能想给它们不同的通道。
         */
        @Override
        public String eventType() {
            return FORMED.equals(status) ? "GROUP_FORMED" : "GROUP_FAILED";
        }
    }
}
