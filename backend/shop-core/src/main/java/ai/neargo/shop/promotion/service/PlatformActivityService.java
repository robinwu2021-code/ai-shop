package ai.neargo.shop.promotion.service;

import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollCommand;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollmentVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformDraft;

import java.util.List;

/**
 * 平台活动与报名（详细设计 §1.6 · 原型 s27–s30 · 开发计划 P3）。
 *
 * <p>平台活动与商家活动是<b>同一个模型</b>（{@code pmt_activity}，owner = PLATFORM）；
 * 多出来的只有出资、预算、报名。它对某家店生效的前提是那家店报了名且审核通过 ——
 * 生效范围是报名里的那几件货，限量是报名的份数。
 */
public interface PlatformActivityService {

    // ---------------------------------------------------------------- 运营（s29 · s30）

    /** 全部平台活动（含草稿），新的在前 */
    List<PlatformActivityVO> opsList();

    /** 新建或修改；{@code publish} 为真即发布报名。已发布的活动只能改报名截止与预算 */
    PlatformActivityVO save(PlatformDraft draft, String operatorNo);

    /** 一个平台活动的报名；status 为空给全部 */
    List<EnrollmentVO> enrollments(String activityNo, String status);

    /**
     * 审核一份报名。通过 = 带条件 UPDATE 占平台预算（超了拒 {@code ENROLLMENT_OVER_BUDGET}）；
     * 驳回理由必填（商家原样看到）。只有待审的能审。
     */
    EnrollmentVO review(String enrollmentNo, boolean pass, String reason, String operatorNo);

    // ---------------------------------------------------------------- 商家（s27 · s28）

    /**
     * 商家看得到的平台活动（已发布的）。
     *
     * @param tab ENROLLABLE（还能报）/ ENROLLED（我报过、没结束）/ ENDED（已结束）；空 = 全部
     */
    List<PlatformActivityVO> forMerchant(String entityNo, String tab);

    PlatformActivityVO detailForMerchant(String entityNo, String activityNo);

    /**
     * 报名（或在审核前改报名）。过了截止或不满足门槛 {@code ENROLLMENT_CLOSED}；已通过的不能改。
     * 两份「最多」在这一刻算定。
     */
    EnrollmentVO enroll(String entityNo, String activityNo, EnrollCommand cmd);

    /** 撤回待审的报名 */
    EnrollmentVO withdraw(String entityNo, String activityNo);
}
