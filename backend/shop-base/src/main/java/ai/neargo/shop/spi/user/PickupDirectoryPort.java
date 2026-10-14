package ai.neargo.shop.spi.user;

import java.util.List;

/**
 * community → fulfillment：<b>全平台自提点名录</b>（P-5.1.2 / P-5.1.3）。
 *
 * <p>与 {@link PickupQueryPort#find} 的区别不是「一个 vs 多个」，是<b>视角不同</b>：
 * 那个回答「这个点的计费口径与归属是什么」（结算与核销要的），
 * 这个回答「平台上有哪些点、各属哪个社区、社区叫什么」（调度看板要的）。
 *
 * <p><b>为什么不给 {@code PickupBrief} 加一个 communityName 字段</b>：
 * 那个 record 有十个参数、被结算与核销两条链路用着，加一个参数要改所有构造点，
 * 而它们一个都不需要社区名。视角不同就分两个 Port —— 这样将来任一边加字段，
 * 另一边不会被迫跟着改。
 */
public interface PickupDirectoryPort {

    /**
     * 常驻自提点名录。
     *
     * <p><b>只给 {@code scope=PERMANENT}</b>：团粒度临时点（{@code GROUP_INSTANCE}，
     * ADR-005）随团生随团灭，把它们混进调度看板的结果是运营每天看到一堆
     * 昨天还在、今天已经消失的点，而那些点上根本不会有配车。
     *
     * <p><b>含停用的点</b>：治理视角不能看不见被自己停掉的点 ——
     * 停用之前下的单还在那里等人取。
     *
     * @param communityNo 限定社区；空 = 全部
     */
    List<PickupRow> list(String communityNo);

    /**
     * @param communityName 社区名快照。<b>看板上按名字读</b>，只给号的话运营要自己去翻对照表
     * @param status        ACTIVE / 其它。停用的点仍然返回，由调用方决定怎么标
     */
    record PickupRow(String pickupNo, String name, String communityNo, String communityName,
                     String type, String status) {
    }
}
