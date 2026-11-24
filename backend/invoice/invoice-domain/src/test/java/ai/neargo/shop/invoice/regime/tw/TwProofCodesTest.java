package ai.neargo.shop.invoice.regime.tw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 证明联条码文本（PRD AC6，TDD §2.3）。 */
class TwProofCodesTest {

    static final Instant AT = Instant.parse("2026-11-21T06:00:00Z");
    static final String VERIFY = "q+EUHVK3fkb8T/w1Xfq2A==XX".substring(0, 24);

    @Test
    void barcodeIsPeriodNumberRandomNineteenChars() {
        String code = TwProofCodes.barcode("11512", "AB12345678", "1234");
        assertEquals("11512AB123456781234", code);
        assertEquals(19, code.length());
    }

    @Test
    void leftQrHeaderCarriesNumberDateRandomHexAmountsAndTaxIds() {
        var qr = TwProofCodes.qr("AB12345678", AT, "1234", 1000, 1050, "22099131", "12345675", VERIFY,
                List.of(new TwProofCodes.Item("咖啡:大杯", BigDecimal.valueOf(2), new BigDecimal("525"))));
        String expectedHead = "AB12345678" + "1151121" + "1234" + "000003e8" + "0000041a" + "22099131" + "12345675"
                + VERIFY;
        assertEquals(77, expectedHead.length());
        assertEquals(expectedHead + ":**********:1:1:1:咖啡：大杯:2:525", qr.left());
        assertEquals("**", qr.right());
        var consumer = TwProofCodes.qr("AB12345678", AT, "1234", 1050, 1050, null, "12345675", VERIFY,
                List.of(new TwProofCodes.Item("茶", BigDecimal.ONE, BigDecimal.TEN)));
        assertTrue(consumer.left().startsWith("AB1234567811511211234" + "0000041a0000041a" + "00000000"));
    }

    @Test
    void itemsOverflowIntoRightQrAndCountsTellHowManyWereEncoded() {
        var items = new ArrayList<TwProofCodes.Item>();
        for (int i = 0; i < 40; i++)
            items.add(new TwProofCodes.Item("招牌牛肉麵加大" + i, BigDecimal.ONE, BigDecimal.valueOf(180)));
        var qr = TwProofCodes.qr("AB12345678", AT, "1234", 7200, 7200, null, "12345675", VERIFY, items);
        assertTrue(qr.left().getBytes(StandardCharsets.UTF_8).length <= TwProofCodes.QR_BYTES + 4);
        assertTrue(qr.right().startsWith("**招牌牛肉麵加大"));
        assertTrue(qr.right().getBytes(StandardCharsets.UTF_8).length <= TwProofCodes.QR_BYTES);
        var counts = qr.left().substring(77).split(":");
        int encoded = Integer.parseInt(counts[2]);
        assertEquals("40", counts[3], "总品目笔数如实写");
        int inLeft = (counts.length - 5) / 3;
        int inRight = qr.right().substring(2).split(":").length / 3;
        assertEquals(encoded, inLeft + inRight);
        assertTrue(encoded < 40);
    }
}
