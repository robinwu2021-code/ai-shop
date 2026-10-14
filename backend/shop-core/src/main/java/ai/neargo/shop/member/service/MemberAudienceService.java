package ai.neargo.shop.member.service;

import ai.neargo.shop.member.dto.MemberVOs.AudiencePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.BatchTagVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentDetailVO;
import ai.neargo.shop.member.dto.MemberVOs.TagUsageVO;
import ai.neargo.shop.spi.member.MemberQueryPort.AudienceItem;

import java.util.List;

/**
 * 会员作为营销目标的那一层：选人试算、批量打标、标签与人群「用在哪」、合并时改指引用。
 *
 * <p>单独成一个服务是为了<b>不在标签、人群、会员三个服务之间绕出循环依赖</b>
 * （会员 → 标签 → 人群 → 会员）。这里只编排，口径仍各在各的服务里。
 */
public interface MemberAudienceService {

    /**
     * 选人面板的试算。
     *
     * @param forActivity 活动场景：只给命中数（活动不推送，没有「收得到」一说），且允许「非本店会员」
     * @param scene       发消息时给场景，频次闸按它判；其余给 null
     */
    AudiencePreviewVO preview(String entityNo, List<AudienceItem> items, String scene, boolean forActivity);

    /**
     * 批量打 / 去一个标签。两种圈人方式二选一：给会员号（效果页「下单的 3 人」），
     * 或给筛选条件（会员名单「这 37 人」）—— 后者当场按条件筛，不信前端传来的名单。
     */
    BatchTagVO batchTag(String entityNo, List<String> memberNos, MemberQuery rule, String scopeStoreNo,
                        String tagNo, boolean add, boolean confirm, String operatorNo);

    TagUsageVO tagUsage(String entityNo, String tagNo);

    SegmentDetailVO segmentDetail(String entityNo, String segmentNo);

    /**
     * 合并标签，并把引用源标签的人群条件与活动受众一起改指到目标标签。
     * 试算时 {@code referencedActivities} 是会被改指的活动数，确认框上要写出来。
     */
    MergePreviewVO mergeTag(String entityNo, String fromTagNo, String intoTagNo, boolean confirm,
                            String operatorNo);
}
