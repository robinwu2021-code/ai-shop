package ai.neargo.shop.invoice.regime.tw;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 电子发票证明联上的一维码与两个 QR 码的文本内容（财政部「电子发票证明联一维及二维条码规格」）。
 *
 * <p>只产出文本；画成图由打印中心的 {@code barcode} / {@code qrPair} 版面块负责 —— 规格属于税制，画法属于打印。
 */
public final class TwProofCodes {

    /** 单个 QR 码的明细预算（UTF-8 字节）。证明联上的 QR 约 2 cm，再多热感纸就扫不出了。 */
    static final int QR_BYTES = 200;

    private TwProofCodes() {
    }

    /** 期别 5 + 号码 10 + 随机码 4 = 19 码（Code39）。 */
    public static String barcode(String periodCode, String fiscalNumber, String randomCode) {
        return periodCode + fiscalNumber + randomCode;
    }

    /** 一行品目：名称、数量、单价（元）。 */
    public record Item(String name, BigDecimal quantity, BigDecimal unitPrice) {}

    public record Qr(String left, String right) {}

    /**
     * @param salesDollars 销售额（元）；台湾 B2C 为含税总计，B2B 为未税
     * @param buyerTaxId 无则传 null（码内写 00000000）
     * @param verification {@link TwQrCipher#verification} 的 24 字符结果
     */
    public static Qr qr(String fiscalNumber, Instant issuedAt, String randomCode, long salesDollars,
            long totalDollars, String buyerTaxId, String sellerTaxId, String verification, List<Item> items) {
        String head = fiscalNumber + TwPeriods.rocDate(issuedAt) + randomCode
                + "%08x".formatted(salesDollars) + "%08x".formatted(totalDollars)
                + (buyerTaxId == null ? "00000000" : buyerTaxId) + sellerTaxId + verification;
        var texts = new ArrayList<String>();
        for (var item : items) texts.add(text(item));
        var left = new StringBuilder();
        var right = new StringBuilder("**");
        int inLeft = 0, inRight = 0;
        boolean spilled = false;
        String prefix = head + ":**********:";
        for (var t : texts) {
            if (!spilled && bytes(prefix + count(inLeft + 1, texts.size()) + left + ":" + t) <= QR_BYTES) {
                left.append(':').append(t);
                inLeft++;
                continue;
            }
            spilled = true;
            if (bytes(right + ":" + t) > QR_BYTES) break;
            right.append(inRight == 0 ? "" : ":").append(t);
            inRight++;
        }
        int encoded = inLeft + inRight;
        return new Qr(prefix + count(encoded, texts.size()) + left, right.toString());
    }

    private static String count(int encoded, int total) {
        return encoded + ":" + total + ":1";
    }

    private static String text(Item item) {
        return item.name().replace(':', '：') + ":" + item.quantity().stripTrailingZeros().toPlainString()
                + ":" + item.unitPrice().stripTrailingZeros().toPlainString();
    }

    private static int bytes(CharSequence s) {
        return s.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
