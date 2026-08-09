package ai.neargo.shop.platform;

import ai.neargo.shop.platform.dto.OpsVOs.AuditLogVO;
import ai.neargo.shop.platform.dto.OpsVOs.LoginResultVO;
import ai.neargo.shop.platform.dto.OpsVOs.MerchantApplyVO;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;

import ai.neargo.shop.common.PageData;

import java.util.List;

/** 平台端（[API 清单 §4.1 / §4.11]）。 */
public interface OpsService {

    LoginResultVO login(String username, String password);

    StaffVO me();

    List<StaffVO> staffList();

    List<MerchantApplyVO> applyQueue();

    /**
     * 入驻申请检索（运营台）。
     *
     * <p>与 {@link #applyQueue()} 的差别：那个只给待办两档（PENDING/REVIEWING），
     * 这个能按状态翻历史。**已处理的申请也要查得到** ——
     * 「这家店当初是谁批的、为什么驳回」是最常见的一类追溯，
     * 只留待办的话这些问题只能去翻审计日志。
     *
     * @param status  逗号分隔的状态；空表示只看待办两档
     * @param keyword 店名/联系人/手机号模糊匹配，空表示不过滤
     */
    PageData<MerchantApplyVO> searchApplies(String status, String keyword, long page, long size);

    /**
     * 审核入驻。**驳回必须写理由**；通过才创建商家主体。
     *
     * @param serviceScope  通过时的服务范围。为空则用申请单上的值。
     * @param communityNos  通过时的覆盖社区，同上。
     *
     *                      <p><b>为什么审核时要能改这两项</b>：商家申请时可以不填
     *                      （ADR-009 允许留空），而<b>通过时必须确定</b> ——
     *                      否则商家上着架却对谁都不可见，且没有任何报错。
     *                      此前运营侧没有这个入口，于是这两项**没有任何地方能填**：
     *                      B 端不填、运营补不了，商家开完店等着一个永远不来的订单。
     */
    void auditApply(String applyNo, boolean approved, String reason,
                    String serviceScope, List<String> communityNos);

    /**
     * 受理申请：PENDING → REVIEWING。
     *
     * <p>这一步<b>不改变审核结果，只改变商家的体感</b>：提交后一直显示「待审核」，
     * 商家不知道有没有人在看，只能打电话问运营。客服接手时点一下，那边就有反馈。
     *
     * <p>因此它<b>不是必经步骤</b>（PENDING 可直接 APPROVED）—— 强制走一遍，
     * 就会有人为了走流程而点，REVIEWING 也就没有信息量了。
     */
    void acceptApply(String applyNo);

    /** C 端提交入驻申请（由 /mp/merchant/apply 调用）。 */
    String createApply(SubmitApplyCommand cmd);

    /**
     * 我的入驻申请状态（C 端）。<b>此前提交完就查不到了</b> ——
     * 商家不知道审到哪一步，只能打电话问运营。
     *
     * @return 没申请过时为 null —— 「没申请过」是正常状态，不是异常
     */
    MerchantApplyVO myApply(String userNo);

    /**
     * @param communityNos 覆盖社区。申请时可空，<b>但审核通过时必须有</b>（ADR-009）——
     *                     否则商家上着架却对谁都不可见
     */
    record SubmitApplyCommand(String userNo, String name, String subject,
                              String contactName, String contactPhone,
                              String category, String description,
                              String serviceScope, List<String> communityNos,
                              List<String> qualifications,
                              /* 承接自提点的意愿（ADR-005）。仅记录，建点由运营另行处理 */
                              boolean asPickupPoint,
                              /*
                               * 行业（sys_industry.industry）。**它决定可选的主体类型** ——
                               * 微信小微的准入白名单按行业给，线上业态不支持小微。
                               * 提交时就要校验，而不是等到进件那一刻：那时入驻早已通过，
                               * 商家已经在上架商品了，再告诉他"你这行不能用这个主体"，
                               * 要么改主体重新走资质，要么这家店根本收不了款。
                               */
                              String industry) {
    }

    /** 写审计。高危操作必须调用。 */
    void audit(String action, String target, String detail);

    List<AuditLogVO> auditLogs(String target);
}
