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

    /**
     * 启停员工。<b>软删除语义</b> —— 账号留着，审计要能追溯到人。
     *
     * <p>不能停用自己：超管把自己停了就没人能改回来，
     * 而拦住的成本远低于事后从库里恢复。
     */
    StaffVO setStaffEnabled(String staffNo, boolean enabled);

    /**
     * 改角色。
     *
     * <p><b>必须校验角色码真实存在于 {@code Perms.ROLE_PERMS}</b>：
     * 写进一个不存在的角色，这个账号的 perms 会是空集 ——
     * 他能登录、导航全空、页面上看不出任何原因。
     */
    @Deprecated(forRemoval = true)
    StaffVO setStaffRole(String staffNo, String role);

    /**
     * 新建员工。<b>返回一次性初始密码</b>，之后再也取不到。
     *
     * <p>密码由后端生成而不是让调用方传：让界面收明文的问题不是加密与否，
     * 是<b>谁都能在 devtools 里看到刚给同事设的密码</b>，
     * 而且它会顺着请求体进日志。生成 + 一次性返回 + 首登强制改密，
     * 把那个口令的有效期压到「第一次登录之前」。
     */
    CreatedStaffVO createStaff(String username, String realName, List<String> roles);

    /**
     * 改角色（<b>多角色</b>）。权限取所有角色的并集。
     *
     * <p>取代单角色的 {@link #setStaffRole}：库早就支持多角色
     * （{@code sys_role_member} 的唯一键含 role_code、{@code roles} 是 JSON 数组、
     * {@code Perms.of} 收 List 并取并集），是写接口把它压成了单值。
     *
     * <p><b>不能给自己加角色</b> —— 单角色版靠「不能改自己」挡住了这件事，
     * 改成多角色时最容易漏的就是它：有 {@code iam:staff:update} 的人
     * 可以顺手给自己加超管。
     */
    StaffVO setStaffRoles(String staffNo, List<String> roles);

    /** 改自己的密码。首登被 {@code mustChangePassword} 卡住时也走这条。 */
    void changeOwnPassword(String oldPassword, String newPassword);

    /**
     * 配数据域。空字符串 / null = 不限定。
     *
     * <p><b>给全量角色（超管等）配数据域直接拒绝</b> ——
     * 存下来会让人以为它被限制了，而实际没有。
     *
     * <p>⚠️ 本批<b>只存不用</b>：各域查询还没按它裁剪。
     */
    StaffVO setStaffScope(String staffNo, String merchantNo, String communityNo, String pickupNo);

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
    /** 新建员工的返回。{@code initialPassword} <b>只在这一次出现</b>。 */
    record CreatedStaffVO(StaffVO staff, String initialPassword) {
    }

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
