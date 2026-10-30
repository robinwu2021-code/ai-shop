package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.svc.ExecutionOriginalCodec;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import java.util.Objects;

/** Original-purpose machine evidence never carries or recreates a human delegated identity. */
public final class ExecutionOriginalProtocol {
    private ExecutionOriginalProtocol() {}

    /** Only the two consumed original read routes participate in version one. */
    public record Binding(int protocolVersion, String endpoint, String action, ExecutionScope scope,
            String executionNo, String executionBindingHash) {
        public Binding {
            if (protocolVersion != 1 || !("SERVICE_EXECUTION".equals(endpoint) && "QUERY_ORIGINAL".equals(action)
                    || "SERVICE_EXECUTION_SOURCE".equals(endpoint) && "INSPECT_ORIGINAL".equals(action))) {
                throw new IllegalArgumentException("invalid original-purpose route");
            }
            Objects.requireNonNull(scope, "scope");
            ServiceExecutionContract.reference(executionNo, 32);
            ServiceExecutionContract.hash(executionBindingHash);
        }
    }

    /** Complete persisted coordinates must match their outer target; there is no self-referential digest. */
    @JsonSerialize(using = ExecutionOriginalCodec.CallSerializer.class)
    @JsonDeserialize(using = ExecutionOriginalCodec.CallDeserializer.class)
    public record Call<T>(Binding binding, T request) {
        public Call {
            Objects.requireNonNull(binding, "binding");
            ExecutionBinding original;
            if (request instanceof ExecutionLookup lookup && "QUERY_ORIGINAL".equals(binding.action())) {
                original = lookup.binding();
            } else if (request instanceof StartIntentLookup lookup && "INSPECT_ORIGINAL".equals(binding.action())) {
                original = lookup.binding();
            } else {
                throw new IllegalArgumentException("typed original lookup required");
            }
            if (!binding.scope().equals(original.scope()) || !binding.executionNo().equals(original.executionNo())
                    || !binding.executionBindingHash().equals(ServiceExecutionContract.bindingHash(original))) {
                throw new IllegalArgumentException("original coordinates mismatch");
            }
        }

        @Override
        public String toString() {
            return "ExecutionOriginalCall[REDACTED]";
        }
    }

    /** Replies bind the server's decoded original request without returning its private values. */
    @JsonSerialize(using = ExecutionOriginalCodec.ReplySerializer.class)
    @JsonDeserialize(using = ExecutionOriginalCodec.ReplyDeserializer.class)
    public record Reply<T>(Binding binding, String requestDigest, T result) {
        public Reply {
            Objects.requireNonNull(binding, "binding");
            ServiceExecutionContract.hash(requestDigest);
            if (result == null || !result.getClass().isRecord()) {
                throw new IllegalArgumentException("typed original reply required");
            }
        }

        @Override
        public String toString() {
            return "ExecutionOriginalReply[REDACTED]";
        }
    }
}
