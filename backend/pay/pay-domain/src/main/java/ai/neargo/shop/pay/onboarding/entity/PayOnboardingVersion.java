package ai.neargo.shop.pay.onboarding.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_onboarding_version（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：进件提交版本：每次提交一行，外部单号每次唯一（修 R04）；驳回原因随版本保留（修 R23）
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_onboarding_version")
public class PayOnboardingVersion extends PayBaseEntity {

    /** 进件单 pay_onboarding.id */
    private Long payOnboardingId;

    /** 版本序号，从 1 起 */
    private Integer versionIndex;

    /** 提交给渠道的外部单号（out_request_no / out_biz_no），每版本唯一 */
    private String outRequestNo;

    /** 本版资料规范化摘要，防止提交后资料被改 */
    private String materialsSha256;

    /** DRAFT / SUBMITTED / CHANNEL_REVIEW / NEED_SIGN / APPROVED / REJECTED / SUPERSEDED */
    private String status;

    /** 渠道申请单号 */
    private String channelApplyNo;

    /** 渠道结果码 */
    private String resultCode;

    /** 该版本驳回原因 */
    private String rejectReason;

    /** 提交人（审计字段） */
    private String submittedBy;

    /** 提交时间 */
    private LocalDateTime submittedAt;

    /** 终态时间 */
    private LocalDateTime decidedAt;
}
