package ai.neargo.shop.invoice.regime.tw;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.entity.IvcAllowance;
import ai.neargo.shop.invoice.entity.IvcInvoice;
import ai.neargo.shop.invoice.entity.IvcInvoiceLine;
import ai.neargo.shop.invoice.entity.IvcRegimeProfile;
import ai.neargo.shop.invoice.regime.InvoiceRegime;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import ai.neargo.shop.spi.invoice.InvoiceSourcePort;
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
import java.util.TreeSet;
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
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DAY_SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

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

    public Amounts amounts(List<InvoiceSourcePort.Line> lines, Buyer buyer) {
        long taxable = 0, zero = 0, free = 0;
        var types = new TreeSet<String>();
        for (var l : lines) {
            long amount = dollars(l.amountMinor());
            switch (l.taxType()) {
                case InvoiceSourcePort.Line.TAXABLE -> taxable += amount;
                case InvoiceSourcePort.Line.ZERO -> zero += amount;
                case InvoiceSourcePort.Line.EXEMPT -> free += amount;
                default -> throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
            }
            types.add(l.taxType());
        }
        // 某一税别合计为负（折扣大过该税别商品）在 MIG 里表达不了，不开
        if (taxable < 0 || zero < 0 || free < 0) throw BizException.of(ErrorCode.INVOICE_SOURCE_NOT_READY);
        long tax = TwBuyerRules.present(buyer.taxId()) ? taxIn(taxable) : 0;
        long total = taxable + zero + free;
        return new Amounts((taxable - tax) * 100, zero * 100, free * 100, tax * 100, total * 100,
                types.size() == 1 ? types.first() : "MIXED", RATE_BP);
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
            if (inv.getZeroTaxSalesMinor() != 0) out.put("zeroTaxSales", Long.toString(dollars(inv.getZeroTaxSalesMinor())));
            if (inv.getFreeTaxSalesMinor() != 0) out.put("freeTaxSales", Long.toString(dollars(inv.getFreeTaxSalesMinor())));
        }
        out.put("barcode", TwProofCodes.barcode(inv.getPeriodCode(), inv.getFiscalNumber(), inv.getRandomCode()));
        if (qrKey != null) {
            var items = new ArrayList<TwProofCodes.Item>();
            for (var l : lines)
                items.add(new TwProofCodes.Item(l.getName(), l.getQuantity(), yuan(l.getUnitPriceMinor())));
            var qr = TwProofCodes.qr(inv.getFiscalNumber(), issued, inv.getRandomCode(),
                    dollars(inv.getSalesMinor() + inv.getZeroTaxSalesMinor() + inv.getFreeTaxSalesMinor()),
                    dollars(inv.getTotalMinor()), inv.getBuyerTaxId(),
                    inv.getSellerTaxId(),
                    TwQrCipher.verification(qrKey, inv.getFiscalNumber(), inv.getRandomCode()), items);
            out.put("qrLeft", qr.left());
            out.put("qrRight", qr.right());
        }
        return out;
    }

    public Map<String, Object> detail(IvcInvoice inv) {
        var out = new LinkedHashMap<String, Object>();
        out.put("title", "交易明細");
        out.put("timezone", TwPeriods.TAIPEI.getId());
        out.put("sellerName", inv.getSellerName());
        out.put("sellerTaxId", inv.getSellerTaxId());
        out.put("fiscalNumber", inv.getFiscalNumber().substring(0, 2) + "-" + inv.getFiscalNumber().substring(2));
        out.put("periodLabel", TwPeriods.label(inv.getPeriodCode()));
        out.put("issuedAt", SHOWN.format(TwPeriods.local(inv.getIssuedAt().toInstant(ZoneOffset.UTC))));
        out.put("total", Long.toString(dollars(inv.getTotalMinor())));
        if ("B2B".equals(inv.getBuyerType())) {
            out.put("buyerTaxId", inv.getBuyerTaxId());
            out.put("sales", Long.toString(dollars(inv.getSalesMinor())));
            out.put("tax", Long.toString(dollars(inv.getTaxMinor())));
        }
        out.put("keepNote", keepNote(inv));
        return out;
    }

    /** 这张发票去了哪：顾客拿着明细要知道该去哪里找发票。 */
    static String keepNote(IvcInvoice inv) {
        if (inv.getDonateCode() != null) return "發票已捐贈：愛心碼 " + inv.getDonateCode();
        if (inv.getCarrierType() != null) return switch (inv.getCarrierType()) {
            case TwBuyerRules.MOBILE -> "發票已存入手機條碼載具 " + inv.getCarrierId();
            case TwBuyerRules.CITIZEN -> "發票已存入自然人憑證載具";
            default -> "發票已存入會員載具";
        };
        return "本明細非發票，請收執電子發票證明聯";
    }

    public Map<String, Object> allowanceProof(IvcInvoice inv, IvcAllowance a) {
        var out = new LinkedHashMap<String, Object>();
        out.put("title", "營業人銷貨退回、進貨退出或折讓證明單");
        out.put("timezone", TwPeriods.TAIPEI.getId());
        out.put("sellerName", inv.getSellerName());
        out.put("sellerTaxId", inv.getSellerTaxId());
        if (inv.getBuyerTaxId() != null) out.put("buyerTaxId", inv.getBuyerTaxId());
        out.put("allowanceNo", a.getFiscalAllowanceNumber() != null ? a.getFiscalAllowanceNumber() : a.getAllowanceNo());
        out.put("allowanceDate", DAY_SHOWN.format(TwPeriods.local(a.getCreatedAt().toInstant(ZoneOffset.UTC))));
        out.put("originalNumber", inv.getFiscalNumber().substring(0, 2) + "-" + inv.getFiscalNumber().substring(2));
        out.put("originalDate", DAY_SHOWN.format(TwPeriods.local(inv.getIssuedAt().toInstant(ZoneOffset.UTC))));
        out.put("reason", a.getReason());
        out.put("net", Long.toString(dollars(a.getAmountMinor() - a.getTaxMinor())));
        out.put("tax", Long.toString(dollars(a.getTaxMinor())));
        out.put("total", Long.toString(dollars(a.getAmountMinor())));
        out.put("signature", "買受人簽收：＿＿＿＿＿＿＿＿");
        return out;
    }

    public boolean voidable(IvcInvoice invoice, Instant now) {
        return invoice.getPeriodCode().equals(periodOf(now));
    }

    /** 折让中所含税额：按原发票应税（含税）占比摊出应税部分，再取其中 5%。 */
    public long allowanceTax(IvcInvoice invoice, long amountMinor) {
        long total = dollars(invoice.getTotalMinor());
        long taxableGross = dollars(invoice.getSalesMinor() + invoice.getTaxMinor());
        long share = total == 0 ? 0 : BigDecimal.valueOf(dollars(amountMinor)).multiply(BigDecimal.valueOf(taxableGross))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValue();
        return taxIn(share) * 100;
    }

    /**
     * MIG 4.x 报文，结构与 XML 元素一一对应（Main / Details / Amount），通道按自己的协议序列化：
     * Turnkey 直接转成 XML 落目录，加值中心可转成它们的 JSON。顶层 {@code mig} 是报文代号，不是元素。
     */
    public Map<String, Object> message(String type, IvcInvoice inv, List<IvcInvoiceLine> lines,
            IvcAllowance allowance) {
        var m = new LinkedHashMap<String, Object>();
        var issued = TwPeriods.local(inv.getIssuedAt().toInstant(ZoneOffset.UTC));
        boolean b2b = "B2B".equals(inv.getBuyerType());
        switch (type) {
            case "ISSUE" -> {
                m.put("mig", "F0401");
                var main = new LinkedHashMap<String, Object>();
                main.put("InvoiceNumber", inv.getFiscalNumber());
                main.put("InvoiceDate", DAY.format(issued));
                main.put("InvoiceTime", TIME.format(issued));
                main.put("Seller", party(inv.getSellerTaxId(), inv.getSellerName()));
                main.put("Buyer", buyer(inv));
                main.put("InvoiceType", "07");
                main.put("DonateMark", inv.getDonateCode() == null ? "0" : "1");
                if (inv.getCarrierType() != null) {
                    main.put("CarrierType", inv.getCarrierType());
                    main.put("CarrierId1", inv.getCarrierId());
                    main.put("CarrierId2", inv.getCarrierId());
                }
                main.put("PrintMark", inv.getPrintMark());
                if (inv.getDonateCode() != null) main.put("NPOBAN", inv.getDonateCode());
                main.put("RandomNumber", inv.getRandomCode());
                m.put("Main", main);
                var items = new ArrayList<Map<String, Object>>();
                for (var l : lines) {
                    var d = new LinkedHashMap<String, Object>();
                    d.put("Description", l.getName());
                    d.put("Quantity", l.getQuantity().stripTrailingZeros().toPlainString());
                    if (l.getUnit() != null) d.put("Unit", l.getUnit());
                    d.put("UnitPrice", yuan(l.getUnitPriceMinor()).toPlainString());
                    d.put("TaxType", migTaxType(l.getTaxType()));
                    d.put("Amount", Long.toString(dollars(l.getAmountMinor())));
                    d.put("SequenceNumber", Integer.toString(l.getLineNo()));
                    items.add(d);
                }
                m.put("Details", Map.of("ProductItem", items));
                var amount = new LinkedHashMap<String, Object>();
                amount.put("SalesAmount", dollars(inv.getSalesMinor()));
                amount.put("FreeTaxSalesAmount", dollars(inv.getFreeTaxSalesMinor()));
                amount.put("ZeroTaxSalesAmount", dollars(inv.getZeroTaxSalesMinor()));
                amount.put("TaxType", migTaxType(inv.getTaxType()));
                amount.put("TaxRate", "0.05");
                amount.put("TaxAmount", dollars(inv.getTaxMinor()));
                amount.put("TotalAmount", dollars(inv.getTotalMinor()));
                m.put("Amount", amount);
            }
            case "VOID" -> {
                m.put("mig", "F0501");
                var voided = TwPeriods.local(inv.getVoidedAt().toInstant(ZoneOffset.UTC));
                m.put("CancelInvoiceNumber", inv.getFiscalNumber());
                m.put("InvoiceDate", DAY.format(issued));
                m.put("BuyerId", b2b ? inv.getBuyerTaxId() : "0000000000");
                m.put("SellerId", inv.getSellerTaxId());
                m.put("CancelDate", DAY.format(voided));
                m.put("CancelTime", TIME.format(voided));
                m.put("CancelReason", inv.getVoidReason());
            }
            case "ALLOWANCE" -> {
                m.put("mig", "G0401");
                var at = TwPeriods.local(allowance.getCreatedAt().toInstant(ZoneOffset.UTC));
                var main = new LinkedHashMap<String, Object>();
                main.put("AllowanceNumber", allowance.getFiscalAllowanceNumber() != null
                        ? allowance.getFiscalAllowanceNumber() : allowance.getAllowanceNo());
                main.put("AllowanceDate", DAY.format(at));
                main.put("Seller", party(inv.getSellerTaxId(), inv.getSellerName()));
                main.put("Buyer", buyer(inv));
                main.put("AllowanceType", "2");
                m.put("Main", main);
                long net = dollars(allowance.getAmountMinor() - allowance.getTaxMinor());
                var item = new LinkedHashMap<String, Object>();
                item.put("OriginalInvoiceDate", DAY.format(issued));
                item.put("OriginalInvoiceNumber", inv.getFiscalNumber());
                item.put("OriginalDescription", allowance.getReason());
                item.put("Quantity", "1");
                item.put("UnitPrice", Long.toString(net));
                item.put("Amount", Long.toString(net));
                item.put("Tax", Long.toString(dollars(allowance.getTaxMinor())));
                item.put("AllowanceSequenceNumber", "1");
                item.put("TaxType", "MIXED".equals(inv.getTaxType()) ? "1" : migTaxType(inv.getTaxType()));
                m.put("Details", Map.of("ProductItem", List.of(item)));
                var amount = new LinkedHashMap<String, Object>();
                amount.put("TaxAmount", dollars(allowance.getTaxMinor()));
                amount.put("TotalAmount", net);
                m.put("Amount", amount);
                // MIG 之外、加值中心要的补充字段（鲸跃折让单带原发票时间）；Turnkey 不输出
                m.put("ext", Map.of("OriginalInvoiceTime", TIME.format(issued)));
            }
            default -> throw new IllegalArgumentException(type);
        }
        return m;
    }

    private static Map<String, Object> party(String id, String name) {
        var p = new LinkedHashMap<String, Object>();
        p.put("Identifier", id);
        p.put("Name", name);
        return p;
    }

    /** 一般消费者：识别码 10 个 0，名称 4 位数字（沿用随机码）。 */
    private static Map<String, Object> buyer(IvcInvoice inv) {
        return "B2B".equals(inv.getBuyerType())
                ? party(inv.getBuyerTaxId(), inv.getBuyerName() == null ? inv.getBuyerTaxId() : inv.getBuyerName())
                : party("0000000000", inv.getRandomCode());
    }

    /** MIG 课税别：1 应税、2 零税率、3 免税、9 混合。 */
    static String migTaxType(String type) {
        return switch (type) {
            case "TAXABLE" -> "1";
            case "ZERO" -> "2";
            case "EXEMPT" -> "3";
            case "MIXED" -> "9";
            default -> throw new IllegalArgumentException(type);
        };
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
