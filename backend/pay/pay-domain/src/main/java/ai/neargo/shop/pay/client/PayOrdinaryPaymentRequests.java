package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.settle.SettlePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/** Internal-only identity facts resolved by core; never accepted from a consumer HTTP payload. */
public final class PayOrdinaryPaymentRequests {
    private PayOrdinaryPaymentRequests() { }

    public record PayerIdentity(String userNo, String orderNo, String openId, String appId, String requestHash) { }

    public record Initiate(SettlePort.PaymentOpen payment, PayerIdentity payer) { }

    public static void requirePayer(SettlePort.PaymentOpen request, PayerIdentity payer) {
        if (request == null || payer == null
                || !Objects.equals(request.userNo(), payer.userNo())
                || !Objects.equals(request.orderNo(), payer.orderNo())
                || !Objects.equals(hash(request), payer.requestHash())) {
            throw new IllegalArgumentException("PAY_PAYER_CONTEXT_CONFLICT");
        }
        if ("WECHAT".equals(request.payChannel())
                && (payer.openId() == null || payer.openId().isBlank()
                    || payer.appId() == null || payer.appId().isBlank())) {
            throw new IllegalArgumentException("PAY_PAYER_IDENTITY_REQUIRED");
        }
    }

    public static String hash(SettlePort.PaymentOpen request) {
        try {
            byte[] canonical = new ObjectMapper().writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
