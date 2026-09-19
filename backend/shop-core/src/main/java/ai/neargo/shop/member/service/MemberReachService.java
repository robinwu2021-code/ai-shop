package ai.neargo.shop.member.service;

import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;

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
    ReachPlan plan(String entityNo, List<AudienceItem> audiences, String scene);

    /** 旧入参：一个人群号（空 = 全部会员）。旧版 App 还在这样调，保留一个版本 */
    default ReachPlan plan(String entityNo, String segmentNo, String scene) {
        return plan(entityNo, legacyAudience(segmentNo), scene);
    }

    /**
     * 真发。<b>幂等窗口在调用方</b>：这里只负责按 {@link #plan} 的结果发，
     * 并把每一条记进 {@code mbr_reach_log}。
     *
     * @return 实际发出多少
     */
    ReachResult send(String entityNo, List<AudienceItem> audiences, String audienceDesc, String scene,
                     String title, String body, String operatorNo);

    /** 不带受众描述的写法（旧版 App 没传）。批次头上的描述由受众项兜底拼出 */
    default ReachResult send(String entityNo, List<AudienceItem> audiences, String scene, String title,
                             String body, String operatorNo) {
        return send(entityNo, audiences, null, scene, title, body, operatorNo);
    }

    /** 「发出去的」列表（原型 m19），新的在前。列表项不带下单名单 */
    List<ReachTaskVO> tasks(String entityNo, long page, long size);

    /** 一次触达的效果（原型 m20）。不是这家店的批次号返回空 */
    java.util.Optional<ReachTaskVO> task(String entityNo, String taskNo);

    /**
     * 买家点推送进了店（C 端）。<b>只认本人</b>：这条触达的会员对不上当前账号就不记，
     * 且不说是哪一种原因 —— 免得 reachNo 被拿来探号。
     *
     * @return 这一下有没有计入
     */
    boolean opened(String reachNo, String userNo);

    /** 效果页的下单名单最多列多少人：再多商家也不会一行行看，要看就存成人群 */
    int ORDERED_MEMBERS_LIMIT = 50;

    /** 旧入参，见 {@link #plan(String, String, String)} */
    default ReachResult send(String entityNo, String segmentNo, String scene, String title,
                             String body, String operatorNo) {
        return send(entityNo, legacyAudience(segmentNo), scene, title, body, operatorNo);
    }

    /** 旧的「一个人群号」换成受众项：空 = 全部会员，与此前 sift 的行为一致 */
    static List<AudienceItem> legacyAudience(String segmentNo) {
        return segmentNo == null || segmentNo.isBlank()
                ? List.of(new AudienceItem(AudienceItem.ALL, "*"))
                : List.of(new AudienceItem(AudienceItem.SEGMENT, segmentNo));
    }

    /**
     * @param reachable  能收到的人数：消息一定进他的小程序消息列表
     * @param pushable   其中有推送设备、还会亮屏提醒的人数
     * @param skips      被拦下的分布。<b>要能说出人话</b>：
     *                   {@code TOO_SOON} 最近发过、{@code OPT_OUT} 已退订、
     *                   {@code LEAD} 线索会员、{@code NO_ACCOUNT} 还没注册、{@code BLOCKED} 被商家拉黑
     */
    record ReachPlan(int matched, int reachable, int pushable, List<Skip> skips) {

        public record Skip(String reason, int count) {
        }
    }

    /**
     * @param taskNo 这一批的号。效果回看按它聚合
     * @param sent   进了买家小程序消息列表的人数
     * @param pushed 其中推送到手机的人数
     */
    record ReachResult(String taskNo, int sent, int pushed, int skipped, List<ReachPlan.Skip> skips) {
    }

    /**
     * 一次触达（原型 m19 / m20）。
     *
     * @param settled        归因窗口已关：数字不会再变。没关时界面写「统计中」——
     *                       免得商家第二天看到 2 单就判定这次失败
     * @param orderedMembers 下单的人，最多 {@link #ORDERED_MEMBERS_LIMIT} 个；列表接口里为空
     * @param notOpened      没来的人数（发出 − 来了）。「没来的存人群」按它
     */
    record ReachTaskVO(String taskNo, String scene, String title, String body, String audienceDesc,
                       long sentAt, long statsUntil, boolean settled,
                       int matched, int sent, int pushed, int skipped, List<ReachPlan.Skip> skips,
                       int opened, int ordered, long orderedAmountMinor,
                       List<OrderedMember> orderedMembers, int notOpened) {
    }

    /** @param name 商家给他记的备注名；没记为空，界面用手机尾号 */
    record OrderedMember(String memberNo, String name, String phoneTail, long amountMinor, long orderedAt) {
    }
}
