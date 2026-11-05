package ai.neargo.shop.pay.merchant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 市场 → 币种要覆盖 sys_market 里的每个市场；缺台湾时，台湾门店开通收款后绑不上收款商户 */
class MerchantDefaultsCurrencyTest {

    @Test
    @DisplayName("★★ 台湾、香港、新加坡与大陆、中东一样有币种")
    void everyMarketHasCurrency() {
        assertEquals("TWD", MerchantDefaults.currencyOf("TW"));
        assertEquals("HKD", MerchantDefaults.currencyOf("HK"));
        assertEquals("SGD", MerchantDefaults.currencyOf("SG"));
        assertEquals("CNY", MerchantDefaults.currencyOf("CN"));
        assertEquals("AED", MerchantDefaults.currencyOf("AE"));
        assertEquals("SAR", MerchantDefaults.currencyOf("SA"));
        assertNull(MerchantDefaults.currencyOf("XX"));
    }
}
