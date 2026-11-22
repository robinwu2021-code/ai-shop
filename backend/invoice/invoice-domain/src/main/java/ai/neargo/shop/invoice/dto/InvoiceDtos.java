package ai.neargo.shop.invoice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 发票域的请求与响应（TDD-发票模块 §2.1）。入参一律不含主体号：主体取自已鉴权上下文。 */
public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    public record BuyerReq(
            @Size(max = 32) String taxId,
            @Size(max = 60) String name,
            @Size(max = 16) String carrierType,
            @Size(max = 64) String carrierId,
            @Size(max = 16) String donateCode) {}

    public record IssueInvoiceReq(
            @NotBlank @Size(max = 32) String sourceType,
            @NotBlank @Size(max = 64) String sourceNo,
            @Valid BuyerReq buyer) {}

    public record VoidInvoiceReq(@NotBlank @Size(max = 256) String reason) {}

    public record AllowanceReq(@NotNull @Positive Long amountMinor, @NotBlank @Size(max = 256) String reason) {}

    /** C 端：保存一个常用载具。会员载具不在这里 —— 它的号码就是登录账号，由服务端填。 */
    public record SaveCarrierReq(
            @NotBlank @Pattern(regexp = "3J0002|CQ0001") String carrierType,
            @NotBlank @Size(max = 64) String carrierId,
            @Size(max = 32) String label,
            Boolean makeDefault) {}

    public record CarrierVO(String carrierNo, String carrierType, String carrierId, String label, boolean isDefault) {}

    /**
     * C 端：给自己的一张订单选发票开法。
     *
     * @param kind CONSUMER 一般（印证明联）/ MEMBER 会员载具 / CARRIER 手机条码或自然人凭证 / TAX_ID 打统编 / DONATE 捐赠
     */
    public record InvoiceChoiceReq(
            @NotBlank @Size(max = 64) String orderNo,
            @NotBlank @Pattern(regexp = "CONSUMER|MEMBER|CARRIER|TAX_ID|DONATE") String kind,
            @Size(max = 16) String carrierType,
            @Size(max = 64) String carrierId,
            @Size(max = 32) String taxId,
            @Size(max = 60) String name,
            @Size(max = 16) String donateCode) {}

    /**
     * 一张订单拆成几张子单（可能分属不同商家），每张各自的结果。
     *
     * @param outcome SAVED 已记下 / ISSUED 已按新选择开立 / ALREADY_ISSUED 已开过票（要改须找商家作废重开）/
     *     NOT_ENABLED 该商家未开通电子发票 / MEMBER_CARRIER_UNAVAILABLE 该商家没有会员载具
     */
    public record ChoiceResultVO(String sourceNo, String outcome, String fiscalNumber) {}

    public record InvoiceQuery(
            @Pattern(regexp = "[0-9]{5}") String periodCode,
            @Pattern(regexp = "ISSUED|VOIDED") String status,
            @Min(1) Integer page,
            @Min(1) @Max(100) Integer size) {}

    public record RegimeProfileReq(
            @NotBlank @Size(max = 16) String regimeCode,
            @NotBlank @Size(max = 32) String sellerTaxId,
            @NotBlank @Size(max = 128) String sellerName,
            @NotBlank @Size(max = 32) String gatewayCode,
            /** 台湾 QR 加密 AES Key（32 位十六进制）；不传 = 不改已存的密钥 */
            @Pattern(regexp = "[0-9A-Fa-f]{32}") String qrAesKeyHex,
            @Size(max = 16) String memberCarrierType,
            /** 付款成功后自动开立；不传 = 关 */
            Boolean autoIssue,
            /** 加值中心分给商家的代号（鲸跃 officeid）；不传 = 清空 */
            @Size(max = 64) String providerAccountCode,
            /** 加值中心账号与密码（鲸跃 posno / posid）：只写不读，两项同传才更新，都不传 = 不改 */
            @Size(max = 64) String providerUser,
            @Size(max = 128) String providerPassword,
            /** 本期剩余号码提醒值；不传 = 不改（新开通默认 50） */
            @Min(0) @Max(100_000) Integer rangeLowWater,
            /** 余量不足 / 期末前自动向加值中心领号；不传 = 不改 */
            Boolean autoFetchRanges) {

        @AssertTrue
        public boolean isProviderCredentialPaired() {
            return (providerUser == null) == (providerPassword == null);
        }
    }

    /** @param periodCode 期别（如 11512）；不传 = 当期 */
    public record ProviderRangeReq(@Pattern(regexp = "\\d{5}") String periodCode) {}

    /** 加值中心账号核验结果；{@code qrKeyStored} = 服务商回传的 QR 金钥已存进 secret 模块。 */
    public record ProviderVerificationVO(String taxId, String name, boolean qrKeyStored) {}

    public record BuyerPreferenceReq(
            @NotBlank @Size(max = 32) String sourceType,
            @NotBlank @Size(max = 64) String sourceNo,
            @NotNull @Valid BuyerReq buyer) {}

    public record NumberRangeReq(
            @NotBlank @Size(max = 16) String regimeCode,
            @NotBlank @Size(max = 8) String periodCode,
            @NotBlank @Size(max = 4) String track,
            @NotNull @Min(0) @Max(99_999_999) Integer startNo,
            @NotNull @Min(0) @Max(99_999_999) Integer endNo,
            /** 空 = 主体内各店共用 */
            @Size(max = 64) String storeNo) {}

    /**
     * 门店上传积压。pending 含待发、发送中、退避中；dueSoon = 已过预警时点未到期限；overdue = 已过法定期限仍未送达。
     * @param oldestDeadlineAt 未送达任务里最早的期限；没有积压为空
     */
    public record UploadBacklogVO(long pending, long held, long needsReview, long dueSoon, long overdue,
            LocalDateTime oldestDeadlineAt) {}

    /**
     * 向服务商查单的结果。
     *
     * @param providerState 服务商侧：ISSUED / VOIDED / PARTIALLY_ALLOWED / FULLY_ALLOWED / NOT_FOUND
     * @param providerCode 服务商原文（鲸跃 0 / 1 / 2 / 3 / nodata）
     * @param expectedState 按本地状态应有的服务商状态
     * @param verdict CONSISTENT 一致 / PENDING_UPLOAD 本地还有未送达的上传（差异待补传后消失）/ MISMATCH 不一致，需人工核对
     * @param reconciled 本次据服务商状态补记为已送达的上传任务（ISSUE / VOID）
     */
    public record ProviderCheckVO(String providerState, String providerCode, String expectedState, String verdict,
            List<String> reconciled, LocalDateTime checkedAt) {}

    public record RegimeProfileVO(
            String regimeCode,
            String sellerTaxId,
            String sellerName,
            String gatewayCode,
            boolean qrKeyConfigured,
            String memberCarrierType,
            boolean autoIssue,
            String status,
            String providerAccountCode,
            boolean providerCredentialConfigured,
            /** 上传暂停原因：账号认证失败时由系统写入，核验通过后清空；空 = 正常 */
            String providerHoldReason,
            LocalDateTime providerHeldAt,
            int rangeLowWater,
            boolean autoFetchRanges,
            /** 本期期别与可用号码数 */
            String currentPeriod,
            long currentRemaining,
            /** 下一期期别与已备号码数（期末前要 > 0） */
            String nextPeriod,
            long nextRemaining) {}

    public record NumberRangeVO(
            String rangeNo,
            String regimeCode,
            String storeNo,
            String periodCode,
            String track,
            int startNo,
            int endNo,
            int nextNo,
            int remaining,
            String status,
            /** MANUAL 商家登记 / PROVIDER 服务商配给 */
            String allocationOwner) {}

    public record InvoiceLineVO(
            int lineNo, String name, BigDecimal quantity, String unit, long unitPriceMinor, long amountMinor,
            String taxType) {}

    /**
     * @param fiscalAllowanceNumber 对外折让单号（发票号码 + 两位序号），上传与证明单上用它
     * @param status ACTIVE / VOIDED
     */
    public record AllowanceVO(String allowanceNo, long amountMinor, long taxMinor, String reason,
            LocalDateTime createdAt, String fiscalAllowanceNumber, String status, String voidReason,
            LocalDateTime voidedAt) {}

    public record InvoiceVO(
            String invoiceNo,
            String regimeCode,
            String industryCode,
            /** 营业人名称与统编（开立当下冻结），C 端「我的发票」要看到是哪家开的 */
            String sellerName,
            String sellerTaxId,
            String sourceType,
            String sourceNo,
            String storeNo,
            String fiscalNumber,
            String periodCode,
            String randomCode,
            LocalDateTime issuedAt,
            String buyerType,
            String buyerTaxId,
            String carrierType,
            String carrierId,
            String donateCode,
            boolean printMark,
            String currency,
            long salesMinor,
            long zeroTaxSalesMinor,
            long freeTaxSalesMinor,
            long taxMinor,
            long totalMinor,
            long allowedMinor,
            String status,
            String voidReason,
            /** 开立报文上传状态：PENDING / SENDING / SENT / FAILED / HELD / NEEDS_REVIEW */
            String uploadStatus,
            /** 开立报文的法定上报期限；已送达后仍保留（看当时是否按期） */
            LocalDateTime uploadDeadlineAt,
            List<InvoiceLineVO> lines,
            List<AllowanceVO> allowances) {}
}
