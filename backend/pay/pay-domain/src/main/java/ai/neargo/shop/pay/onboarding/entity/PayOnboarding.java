package ai.neargo.shop.pay.onboarding.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_onboarding（db/pay V10 · TDD-支付服务-I4进件与收款商户 S1）：进件单：某门店向某渠道申请收款能力。资料与每次提交是版本；成功产出 pay_merchant
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_onboarding")
public class PayOnboarding extends PayBaseEntity {

    /** 进件单号（对外：进件回调地址里使用） */
    private String onboardingNo;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 渠道码 */
    private String payChannel;

    /** 市场码 */
    private String market;

    /** MERCHANT_OWN / SERVICE_PROVIDER / PLATFORM_SUB */
    private String accessMode;

    /** DIRECT / AGGREGATED */
    private String fundsPath;

    /** SERVICE_PROVIDER / PLATFORM_SUB 时挂在哪个平台级收款商户下（pay_merchant.id） */
    private Long parentPayMerchantId;

    /** 通道进件档位（小微 / 个体户 / 企业…），不是法律形态真源 */
    private String legalForm;

    /** 当前资料版本 */
    private Integer currentVersion;

    /** DRAFT / SUBMITTED / CHANNEL_REVIEW / NEED_SIGN / APPROVED / REJECTED / CANCELLED */
    private String status;

    /** 渠道侧申请单号（当前版本） */
    private String channelApplyNo;

    /** NEED_SIGN 时渠道给的签约链接 */
    private String signUrl;

    /** 当前版本驳回原因，原样给商家看；历史原因在版本表 */
    private String rejectReason;

    /** APPROVED 后产出的收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 建单时门店所属主体名快照 */
    private String entityNameSnapshot;

    /** 建单时门店名快照 */
    private String storeNameSnapshot;

    /** BIZ 商家自助 / OPS 运营代办 */
    private String initiatorType;

    /** 发起人（审计字段，与 created_by 同口径，不作关联） */
    private String initiatorNo;

    /** 最近一次提交（UTC） */
    private LocalDateTime submittedAt;

    /** 渠道给出终态的时间 */
    private LocalDateTime decidedAt;

    /** 在途唯一键：SUBMITTED / CHANNEL_REVIEW / NEED_SIGN 时 = store_id|pay_channel，否则 NULL；服务同事务维护（防 R06 重复进件） */
    private String inflightKey;
}
