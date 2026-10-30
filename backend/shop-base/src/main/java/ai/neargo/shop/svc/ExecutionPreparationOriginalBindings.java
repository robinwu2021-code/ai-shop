package ai.neargo.shop.svc;

import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Separate closed observation domains preserve every legacy digest byte. */
public final class ExecutionPreparationOriginalBindings {
    private final String token;

    public ExecutionPreparationOriginalBindings(String token) {
        this.token = token;
    }

    public String digest(EvidenceCall call) {
        return digest("SHOP_EXECUTION_PREPARATION_EVIDENCE_ORIGINAL_V1", call);
    }

    public String digest(StockCall call) {
        return digest("SHOP_EXECUTION_PREPARATION_STOCK_ORIGINAL_V1", call);
    }

    public void require(EvidenceCall call, EvidenceReply reply) {
        if (reply == null
                || !call.binding().equals(reply.binding())
                || !equal(digest(call), reply.requestDigest())
                || !call.request().reference().equals(reply.result().reference())) throw invalid();
    }

    public void require(StockCall call, StockReply reply) {
        if (reply == null
                || !call.binding().equals(reply.binding())
                || !equal(digest(call), reply.requestDigest())) throw invalid();
        reply.result().validate(call.request());
    }

    private String digest(String domain, Object call) {
        if (token == null || token.isBlank() || call == null) throw invalid();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical =
                    domain
                            + "\n"
                            + InternalBindings.canonical(
                                    InternalBindings.tree(call), JsonMapper.builder().build());
            return HexFormat.of()
                    .formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("preparation binding unavailable");
        }
    }

    private static boolean equal(String left, String right) {
        return right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.UTF_8),
                        right.getBytes(StandardCharsets.UTF_8));
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid preparation original message");
    }
}
