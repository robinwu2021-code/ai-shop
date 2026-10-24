package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.settle.SettlePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.ObjectMapper;

/** Internal-only identity facts resolved by core; never accepted from a consumer HTTP payload. */
public final class PayOrdinaryPaymentRequests {
    private PayOrdinaryPaymentRequests() { }

    public record PayerIdentity(String userNo, String orderNo, String openId, String appId, String requestHash) { }

    public record Initiate(SettlePort.PaymentOpen payment, PayerIdentity payer) { }

    public static String hash(SettlePort.PaymentOpen request) {
        try {
            byte[] canonical = new ObjectMapper().writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
