package ai.neargo.shop.invoice.regime.tw;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.regime.InvoiceRegime;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 台湾电子发票（MIG 4.x）。首期唯一税制。
 *
 * <p><b>金额口径</b>：平台金额以 TWD 的 ISO 最小单位存（2 位小数），发票只认整数元，所以每行与总计都必须是整元；
 * 不是整元的来源单不开票，而不是四舍五入出一张与收款不符的发票。
 *
 * <p><b>销售额与税额</b>：MIG 对 B2C 要求销售额填含税总计、税额 0；B2B（打统编）才拆出 5% 营业税，
 * 税额 = 总计 − round(总计 / 1.05)。
 */
@Component
public class TwEguiRegime implements InvoiceRegime {

    public static final String CODE = "TW_EGUI";
    static final int RATE_BP = 500;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String code() {
        return CODE;
    }

    public String periodOf(Instant at) {
        return TwPeriods.periodOf(at);
    }

    public void validateBuyer(Buyer buyer, IvcRegimeProfile profile) {
        TwBuyerRules.validate(buyer, profile.getMemberCarrierType());
    }

    public void validateSeller(String sellerTaxId) {
        if (!TwBuyerRules.validTaxId(sellerTaxId)) throw BizException.of(ErrorCode.INVOICE_BUYER_INVALID);
    }

    public void validateRange(String periodCode, String track, int startNo, int endNo) {
        if (periodCode == null || !periodCode.matches("[0-9]{3}(02|04|06|08|10|12)")
                || track == null || !track.matches("[A-Z]{2}")
                || startNo < 0 || startNo > endNo || endNo > 99_999_999)
            throw BizException.of(ErrorCode.INVOICE_RANGE_INVALID);
    }

    public Amounts amounts(long totalMinor, Buyer buyer) {
        long total = dollars(totalMinor);
        long tax = TwBuyerRules.present(buyer.taxId()) ? taxIn(total) : 0;
        return new Amounts((total - tax) * 100, tax * 100, totalMinor, "TAXABLE", RATE_BP);
    }

    public boolean printMark(Buyer buyer) {
        return !TwBuyerRules.present(buyer.carrierType()) && !TwBuyerRules.present(buyer.donateCode());
    }

    public String buyerType(Buyer buyer) {
        return TwBuyerRules.present(buyer.taxId()) ? "B2B" : "B2C";
    }

    public String randomCode() {
        return "%04d".formatted(RANDOM.nextInt(10_000));
    }

    public String fiscalNumber(String track, int number) {
        return track + "%08d".formatted(number);
    }

    public Map<String, Object> proof(IvcInvoice inv, List<IvcInvoiceLine> lines, byte[] qrKey) {
        Instant issued = inv.getIssuedAt().toInstant(ZoneOffset.UTC);
        boolean b2b = "B2B".equals(inv.getBuyerType());
        var out = new LinkedHashMap<String, Object>();
        out.put("title", "電子發票證明聯");
        out.put("timezone", TwPeriods.TAIPEI.getId());
        out.put("periodLabel", TwPeriods.label(inv.getPeriodCode()));
        out.put("fiscalNumber", inv.getFiscalNumber().substring(0, 2) + "-" + inv.getFiscalNumber().substring(2));
        out.put("issuedAt", SHOWN.format(TwPeriods.local(issued)));
        out.put("randomCode", inv.getRandomCode());
        out.put("total", Long.toString(dollars(inv.getTotalMinor())));
        out.put("sellerTaxId", inv.getSellerTaxId());
        out.put("sellerName", inv.getSellerName());
        if (b2b) {
            out.put("format", "格式 25");
            out.put("buyerTaxId", inv.getBuyerTaxId());
            out.put("sales", Long.toString(dollars(inv.getSalesMinor())));
            out.put("tax", Long.toString(dollars(inv.getTaxMinor())));
        }
        out.put("barcode", TwProofCodes.barcode(inv.getPeriodCode(), inv.getFiscalNumber(), inv.getRandomCode()));
        if (qrKey != null) {
            var items = new ArrayList<TwProofCodes.Item>();
            for (var l : lines)
                items.add(new TwProofCodes.Item(l.getName(), l.getQuantity(), yuan(l.getUnitPriceMinor())));
            var qr = TwProofCodes.qr(inv.getFiscalNumber(), issued, inv.getRandomCode(),
                    dollars(inv.getSalesMinor()), dollars(inv.getTotalMinor()), inv.getBuyerTaxId(),
                    inv.getSellerTaxId(),
                    TwQrCipher.verification(qrKey, inv.getFiscalNumber(), inv.getRandomCode()), items);
            out.put("qrLeft", qr.left());
            out.put("qrRight", qr.right());
        }
        return out;
    }

    public boolean voidable(IvcInvoice invoice, Instant now) {
        return invoice.getPeriodCode().equals(periodOf(now));
    }

    public long allowanceTax(IvcInvoice invoice, long amountMinor) {
        return taxIn(dollars(amountMinor)) * 100;
    }

    public Map<String, Object> message(String type, IvcInvoice inv, List<IvcInvoiceLine> lines,
            IvcAllowance allowance) {
        var m = new LinkedHashMap<String, Object>();
        Instant issued = inv.getIssuedAt().toInstant(ZoneOffset.UTC);
        switch (type) {
            case "ISSUE" -> {
                m.put("mig", "F0401");
                m.put("InvoiceNumber", inv.getFiscalNumber());
                m.put("InvoiceDate", DateTimeFormatter.BASIC_ISO_DATE.format(TwPeriods.local(issued)));
                m.put("InvoiceTime", DateTimeFormatter.ISO_LOCAL_TIME.format(TwPeriods.local(issued).withNano(0)));
                m.put("RandomNumber", inv.getRandomCode());
                m.put("BuyerIdentifier", inv.getBuyerTaxId() == null ? "0000000000" : inv.getBuyerTaxId());
                m.put("BuyerName", inv.getBuyerName() == null ? "0000" : inv.getBuyerName());
                m.put("CarrierType", inv.getCarrierType());
                m.put("CarrierId1", inv.getCarrierId());
                m.put("NPOBAN", inv.getDonateCode());
                m.put("DonateMark", inv.getDonateCode() == null ? "0" : "1");
                m.put("PrintMark", inv.getPrintMark());
                var details = new ArrayList<Map<String, Object>>();
                for (var l : lines) {
                    var d = new LinkedHashMap<String, Object>();
                    d.put("SequenceNumber", l.getLineNo());
                    d.put("Description", l.getName());
                    d.put("Quantity", l.getQuantity().stripTrailingZeros().toPlainString());
                    d.put("Unit", l.getUnit());
                    d.put("UnitPrice", yuan(l.getUnitPriceMinor()).toPlainString());
                    d.put("Amount", Long.toString(dollars(l.getAmountMinor())));
                    d.put("TaxType", "1");
                    details.add(d);
                }
                m.put("Details", details);
                m.put("SalesAmount", dollars(inv.getSalesMinor()));
                m.put("TaxType", "1");
                m.put("TaxRate", "0.05");
                m.put("TaxAmount", dollars(inv.getTaxMinor()));
                m.put("TotalAmount", dollars(inv.getTotalMinor()));
            }
            case "VOID" -> {
                m.put("mig", "F0501");
                m.put("CancelInvoiceNumber", inv.getFiscalNumber());
                m.put("InvoiceDate", DateTimeFormatter.BASIC_ISO_DATE.format(TwPeriods.local(issued)));
                m.put("CancelReason", inv.getVoidReason());
            }
            case "ALLOWANCE" -> {
                m.put("mig", "G0401");
                m.put("AllowanceNumber", allowance.getAllowanceNo());
                m.put("OriginalInvoiceNumber", inv.getFiscalNumber());
                m.put("OriginalInvoiceDate", DateTimeFormatter.BASIC_ISO_DATE.format(TwPeriods.local(issued)));
                m.put("Amount", dollars(allowance.getAmountMinor() - allowance.getTaxMinor()));
                m.put("Tax", dollars(allowance.getTaxMinor()));
                m.put("TotalAmount", dollars(allowance.getAmountMinor()));
                m.put("Reason", allowance.getReason());
            }
            default -> throw new IllegalArgumentException(type);
        }
        m.put("SellerIdentifier", inv.getSellerTaxId());
        return m;
    }

    /** 含税整元金额里的 5% 营业税：总计 − round(总计 / 1.05)。 */
    static long taxIn(long totalDollars) {
        long net = BigDecimal.valueOf(totalDollars).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(105), 0, RoundingMode.HALF_UP).longValue();
        return totalDollars - net;
    }

    static long dollars(long minor) {
        if (minor % 100 != 0) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        return minor / 100;
    }

    private static BigDecimal yuan(long minor) {
        return BigDecimal.valueOf(minor, 2).stripTrailingZeros();
    }
}
