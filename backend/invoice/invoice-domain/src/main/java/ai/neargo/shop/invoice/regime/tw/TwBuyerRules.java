package ai.neargo.shop.invoice.regime.tw;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import java.util.regex.Pattern;

/**
 * 台湾电子发票买受人规则：统一编号检查码、载具格式、爱心码，以及三者的互斥。
 *
 * <p>统编检查码沿财政部 2023 年起的规则：加权和能被 5 整除即有效；第 7 位为 7 时，加权和 + 1 能被 5 整除也有效。
 */
public final class TwBuyerRules {

    /** 手机条码载具类别号。 */
    public static final String MOBILE = "3J0002";
    /** 自然人凭证载具类别号。 */
    public static final String CITIZEN = "CQ0001";

    private static final int[] WEIGHTS = {1, 2, 1, 2, 1, 2, 4, 1};
    private static final Pattern MOBILE_ID = Pattern.compile("/[0-9A-Z.+-]{7}");
    private static final Pattern CITIZEN_ID = Pattern.compile("[A-Z]{2}[0-9]{14}");
    private static final Pattern MEMBER_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");
    private static final Pattern DONATE = Pattern.compile("[0-9]{3,7}");

    private TwBuyerRules() {
    }

    public static boolean validTaxId(String id) {
        if (id == null || !id.matches("[0-9]{8}")) return false;
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            int p = (id.charAt(i) - '0') * WEIGHTS[i];
            sum += p / 10 + p % 10;
        }
        return sum % 5 == 0 || (id.charAt(6) == '7' && (sum + 1) % 5 == 0);
    }

    /** @param memberCarrierType 主体申请的会员载具类别号；未申请为空 */
    public static void validate(Buyer b, String memberCarrierType) {
        boolean taxId = present(b.taxId()), carrier = present(b.carrierType()) || present(b.carrierId()),
                donate = present(b.donateCode());
        if (taxId && !validTaxId(b.taxId())) throw invalid();
        if (donate && (taxId || carrier || !DONATE.matcher(b.donateCode()).matches())) throw invalid();
        if (present(b.name()) && b.name().length() > 60) throw invalid();
        if (!carrier) return;
        if (!present(b.carrierType()) || !present(b.carrierId())) throw invalid();
        boolean ok = switch (b.carrierType()) {
            case MOBILE -> MOBILE_ID.matcher(b.carrierId()).matches();
            case CITIZEN -> CITIZEN_ID.matcher(b.carrierId()).matches();
            default -> b.carrierType().equals(memberCarrierType) && MEMBER_ID.matcher(b.carrierId()).matches();
        };
        if (!ok) throw invalid();
    }

    static boolean present(String s) {
        return s != null && !s.isBlank();
    }

    private static BizException invalid() {
        return BizException.of(ErrorCode.INVOICE_BUYER_INVALID);
    }
}
