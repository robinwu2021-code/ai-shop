package ai.neargo.shop.spi.marketing;

import java.util.List;

/**
 * member → marketing：<b>一个标签 / 人群被谁引用着</b>。
 *
 * <p>商家停用或合并一个标签、改一个人群之前要看见后果：引用它的活动与发券批次在营销域，
 * 会员域看不见。不给他看的话，「爱囤货」停掉那一刻，引用它的活动就一个人都命中不了 ——
 * 活动照样显示「进行中」，零报错。
 */
public interface AudienceRefPort {

    /**
     * 受众里引用了这一项的活动（未结束的：进行中、暂停、未开始）。
     *
     * @param type  TAG / SEGMENT / LEVEL / SOURCE
     * @param value 标签号 / 人群号 / …
     */
    List<AudienceRef> activitiesUsing(String entityNo, String type, String value);

    /** 按这个人群发过的券（新到旧，最多 20 条） */
    List<AudienceRef> couponIssuesUsing(String entityNo, String segmentNo);

    /**
     * 标签合并时把活动受众里的源标签改指到目标标签。
     * 同一个活动两个标签都有时删掉源那一行（唯一键会挡住改指）。
     *
     * @return 改指的活动数
     */
    int retargetTag(String entityNo, String fromTagNo, String toTagNo);

    /**
     * @param kind   ACTIVITY / COUPON_ISSUE
     * @param refNo  活动号 / 发放批次号
     * @param name   活动名 / 券名
     * @param status 活动状态；发放批次为 null
     * @param at     发放时刻；活动为 null
     */
    record AudienceRef(String kind, String refNo, String name, String status, Long at) {
        public static final String ACTIVITY = "ACTIVITY";
        public static final String COUPON_ISSUE = "COUPON_ISSUE";
    }
}
