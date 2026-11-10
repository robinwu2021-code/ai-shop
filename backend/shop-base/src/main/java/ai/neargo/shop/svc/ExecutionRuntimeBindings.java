package ai.neargo.shop.svc;

import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineReply;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Separate finite runtime domains bind complete request bytes without installing saved user
 * authority.
 */
public final class ExecutionRuntimeBindings {
    private final String token;

    public ExecutionRuntimeBindings(String token) {
        this.token = token;
    }

    public String digest(MachineCall<?> call) {
        if (token == null
                || token.isBlank()
                || call == null
                || call.purpose() == null
                || call.reference() == null
                || call.request() == null) throw invalid();
        call.reference().validate();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String canonical =
                    "SHOP_EXECUTION_RUNTIME_V1\n"
                            + call.purpose().name()
                            + "\n"
                            + InternalBindings.canonical(
                                    InternalBindings.tree(call), JsonMapper.builder().build());
            return HexFormat.of()
                    .formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failed) {
            throw new IllegalStateException("runtime binding unavailable");
        }
    }

    public void requireRoute(MachineCall<?> call, Purpose purpose, ExecutionReference reference) {
        if (call == null || call.purpose() != purpose || !call.reference().equals(reference))
            throw invalid();
        reference.validate();
    }

    public <T> MachineReply<T> reply(MachineCall<?> call, T result) {
        if (result == null) throw invalid();
        return new MachineReply<>(
                call.purpose(), call.reference().referenceHash(), digest(call), result);
    }

    public void require(MachineCall<?> call, MachineReply<?> reply) {
        if (reply == null
                || reply.result() == null
                || reply.purpose() != call.purpose()
                || !call.reference().referenceHash().equals(reply.referenceHash())
                || reply.requestDigest() == null
                || !MessageDigest.isEqual(
                        digest(call).getBytes(StandardCharsets.UTF_8),
                        reply.requestDigest().getBytes(StandardCharsets.UTF_8))) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid runtime original binding");
    }
}
