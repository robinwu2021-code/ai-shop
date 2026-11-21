package ai.neargo.shop.invoice.dto;

import jakarta.validation.Valid;
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
            Boolean autoIssue) {}

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

    public record RegimeProfileVO(
            String regimeCode,
            String sellerTaxId,
            String sellerName,
            String gatewayCode,
            boolean qrKeyConfigured,
            String memberCarrierType,
            boolean autoIssue,
            String status) {}

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
            String status) {}

    public record InvoiceLineVO(
            int lineNo, String name, BigDecimal quantity, String unit, long unitPriceMinor, long amountMinor,
            String taxType) {}

    public record AllowanceVO(String allowanceNo, long amountMinor, long taxMinor, String reason,
            LocalDateTime createdAt) {}

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
            /** 开立报文上传状态：PENDING / SENT / FAILED */
            String uploadStatus,
            List<InvoiceLineVO> lines,
            List<AllowanceVO> allowances) {}
}
