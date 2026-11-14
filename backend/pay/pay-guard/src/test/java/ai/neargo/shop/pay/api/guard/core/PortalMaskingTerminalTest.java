package ai.neargo.shop.pay.api.guard.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** POS 终端的请求 / 响应进请求记录前脱敏（pay-guard TDD §2.2 C「卡数据」；开发计划 M6.2） */
class PortalMaskingTerminalTest {

    @Test
    @DisplayName("★★★ 卡数据密文、付款码、店长授权码、激活码、激活响应里的设备密钥都不进请求记录；业务字段照留")
    void terminalSecretsAreMasked() {
        String masked = PortalMasking.mask("{\"encryptedCardData\":\"ENC-ABC\",\"authCode\":\"134567890123\","
                + "\"managerCode\":\"246810\",\"activationCode\":\"K7Q2M9PA3X\",\"deviceSecret\":\"dev-secret-xyz\","
                + "\"amountMinor\":1880,\"payOrderNo\":\"PYO-1\"}");

        assertThat(masked).doesNotContain("ENC-ABC", "134567890123", "246810", "K7Q2M9PA3X", "dev-secret-xyz")
                .contains("\"amountMinor\":1880", "\"payOrderNo\":\"PYO-1\"");
    }
}
