package ai.neargo.shop.invoice.regime.tw;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.invoice.InvoicePort.Buyer;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** 台湾电子发票纯规则（PRD AC4、AC5，TDD §2.3）。 */
class TwRulesTest {

    @Test
    void periodIsRocYearPlusEvenMonthInTaipeiTime() {
        assertEquals("11512", TwPeriods.periodOf(Instant.parse("2026-11-21T06:00:00Z")));
        assertEquals("11502", TwPeriods.periodOf(Instant.parse("2026-01-03T00:00:00Z")));
        // 台北已是 9 月 1 日 → 下一期；按 UTC 算会错算成 11508
        assertEquals("11510", TwPeriods.periodOf(Instant.parse("2026-08-31T16:30:00Z")));
        assertEquals("115年11-12月", TwPeriods.label("11512"));
        assertEquals("115年01-02月", TwPeriods.label("11502"));
        assertEquals("1151121", TwPeriods.rocDate(Instant.parse("2026-11-21T06:00:00Z")));
    }

    @Test
    void taxIdChecksumIncludingTheSeventhDigitSevenRule() {
        assertTrue(TwBuyerRules.validTaxId("22099131"));
        assertTrue(TwBuyerRules.validTaxId("12345675"), "第 7 位为 7：加权和 + 1 能被 5 整除");
        assertFalse(TwBuyerRules.validTaxId("12345678"));
        assertFalse(TwBuyerRules.validTaxId("2209913"));
        assertFalse(TwBuyerRules.validTaxId("2209913A"));
    }

    @Test
    void buyerCombinationsAndCarrierFormats() {
        assertDoesNotThrow(() -> TwBuyerRules.validate(Buyer.consumer(), null));
        assertDoesNotThrow(() -> TwBuyerRules.validate(new Buyer(null, null, "3J0002", "/ABC+123", null), null));
        assertDoesNotThrow(() -> TwBuyerRules.validate(new Buyer(null, null, "CQ0001", "AB12345678901234", null), null));
        assertDoesNotThrow(() -> TwBuyerRules.validate(new Buyer(null, null, "EJ0113", "M0001", null), "EJ0113"));
        assertDoesNotThrow(() -> TwBuyerRules.validate(new Buyer(null, null, null, null, "8585"), null));
        assertDoesNotThrow(() -> TwBuyerRules.validate(new Buyer("22099131", "台積", null, null, null), null));
        invalid(new Buyer("12345678", null, null, null, null));
        invalid(new Buyer(null, null, "3J0002", "/abc1234", null));
        invalid(new Buyer(null, null, "3J0002", null, null));
        invalid(new Buyer(null, null, "EJ0113", "M0001", null));
        invalid(new Buyer(null, null, null, null, "12"));
        invalid(new Buyer("22099131", null, null, null, "8585"));
        invalid(new Buyer(null, null, "3J0002", "/ABC+123", "8585"));
    }

    @Test
    void b2cKeepsTaxInsideSalesAndB2bSplitsFivePercent() {
        var regime = new TwEguiRegime();
        var b2c = regime.amounts(105_000, Buyer.consumer());
        assertEquals(105_000, b2c.salesMinor());
        assertEquals(0, b2c.taxMinor());
        var b2b = regime.amounts(105_000, new Buyer("22099131", null, null, null, null));
        assertEquals(100_000, b2b.salesMinor());
        assertEquals(5_000, b2b.taxMinor());
        assertEquals(5, TwEguiRegime.taxIn(100), "100 元含稅：未稅 round(95.24)=95，稅 5");
        var fraction = assertThrows(BizException.class, () -> regime.amounts(105_050, Buyer.consumer()));
        assertEquals(ErrorCode.INVOICE_SOURCE_NOT_READY, fraction.errorCode());
        assertFalse(regime.printMark(new Buyer(null, null, "3J0002", "/ABC+123", null)));
        assertFalse(regime.printMark(new Buyer(null, null, null, null, "8585")));
        assertTrue(regime.printMark(new Buyer("22099131", null, null, null, null)));
    }

    @Test
    void rangeFormat() {
        var regime = new TwEguiRegime();
        assertDoesNotThrow(() -> regime.validateRange("11512", "AB", 1, 50));
        for (var bad : new Object[][] {{"11511", "AB", 1, 50}, {"11512", "Ab", 1, 50}, {"11512", "AB", 9, 3},
                {"11512", "AB", 0, 100_000_000}}) {
            var e = assertThrows(BizException.class,
                    () -> regime.validateRange((String) bad[0], (String) bad[1], (int) bad[2], (int) bad[3]));
            assertEquals(ErrorCode.INVOICE_RANGE_INVALID, e.errorCode());
        }
    }

    @Test
    void qrVerificationIsAesCbcOfNumberAndRandomWithTheOfficialIv() throws Exception {
        byte[] key = TwQrCipher.parseKey("0123456789ABCDEF0123456789abcdef");
        String v = TwQrCipher.verification(key, "AB12345678", "1234");
        assertEquals(24, v.length());
        var c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Base64.getDecoder().decode("Dt8lyToo17X/XkXaQvihuA==")));
        assertArrayEquals("AB123456781234".getBytes(), c.doFinal(Base64.getDecoder().decode(v)));
        assertEquals(null, TwQrCipher.parseKey("XYZ"));
    }

    private static void invalid(Buyer buyer) {
        var e = assertThrows(BizException.class, () -> TwBuyerRules.validate(buyer, null));
        assertEquals(ErrorCode.INVOICE_BUYER_INVALID, e.errorCode());
    }
}
