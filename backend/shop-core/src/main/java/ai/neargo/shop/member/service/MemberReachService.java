package ai.neargo.shop.member.service;

import java.util.List;

/**
 * 给会员发消息（P7）。
 *
 * <p><b>这是整条线上唯一会打扰真实用户的功能</b>，所以它的默认值全部偏保守：
 * <ol>
 *   <li><b>先算后发</b>（{@link #plan}）：告诉商家这一次真正能发给多少人、
 *       多少人被拦下、分别为什么。只报一个「发送成功」，他会以为人群里每个人都收到了。</li>
 *   <li><b>频次闸按场景分档</b>：公告与唤回不是一回事，一个店一周发三条公告
 *       和一周唤回三次，后者要烦人得多。口径全部读 {@code sys_setting}，
 *       代码里只有 key —— 运营要收紧频次不该等发版。</li>
 *   <li><b>线索一律不发</b>：商家录进来的手机号，本人从没同意过接收任何东西。</li>
 * </ol>
 */
public interface MemberReachService {

    /**
     * 试算：这一次能发给谁、谁被拦下。<b>不发送</b>。
     *
     * @param scene {@code NOTICE} / {@code WAKEUP} / {@code COUPON}
     */
    ReachPlan plan(String entityNo, String segmentNo, String scene);

    /**
     * 真发。<b>幂等窗口在调用方</b>：这里只负责按 {@link #plan} 的结果发，
     * 并把每一条记进 {@code mbr_reach_log}。
     *
     * @return 实际发出多少
     */
    ReachResult send(String entityNo, String segmentNo, String scene, String title,
                     String body, String operatorNo);

    /**
     * @param reachable  能发的人数
     * @param skips      被拦下的分布。<b>要能说出人话</b>：
     *                   {@code TOO_SOON} 最近发过、{@code OPT_OUT} 已退订、
     *                   {@code LEAD} 线索会员、{@code NO_ACCOUNT} 还没注册
     */
    record ReachPlan(int matched, int reachable, List<Skip> skips) {

        public record Skip(String reason, int count) {
        }
    }

    /** @param taskNo 这一批的号。效果回看按它聚合 */
    record ReachResult(String taskNo, int sent, int skipped, List<ReachPlan.Skip> skips) {
    }
}
