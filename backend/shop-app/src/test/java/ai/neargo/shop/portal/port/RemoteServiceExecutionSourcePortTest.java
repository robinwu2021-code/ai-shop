package ai.neargo.shop.portal.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourceInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntentLookup;
import ai.neargo.shop.svc.InternalBindings;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exercises reverse adapter envelope matching; actual Beauty source provider is covered separately. */
class RemoteServiceExecutionSourcePortTest {
    private static final String HASH = "a".repeat(64);
    private final InternalBindings bindings = new InternalBindings("secret");
    private final ExecutionBinding original = new ExecutionBinding(new ExecutionScope("MAIN", "entity", "store"),
            "BEAUTY", "operation", "order", "child", "line", 1, "work", 1, "task", 0, "binding", 1,
            "execution", 1, "submission", "fence", 1, 1, 1, HASH, "definition", HASH, HASH, HASH);

    @Test
    void originalInspectionPreservesContextAndRejectsAnotherOriginalReply() {
        var context = SecurityContextHolder.getContext();
        var adapter = new RemoteServiceExecutionSourcePort(api(false), bindings);
        assertEquals(original, adapter.inspectOriginal(new StartIntentLookup(original)).binding());
        assertSame(context, SecurityContextHolder.getContext());
        assertThrows(BizException.class, () -> new RemoteServiceExecutionSourcePort(api(true), bindings)
                .inspectOriginal(new StartIntentLookup(original)));
    }

    @Test
    void reverseInspectionAdapterCannotFreezeHumanIntent() {
        assertThrows(BizException.class, () -> new RemoteServiceExecutionSourcePort(api(false), bindings)
                .freeze(new FreezeStart(original.scope(), "work", "task", 0, 1, 1, "key")));
    }

    private ServiceExecutionSourceInternalApi api(boolean changedTarget) {
        return new ServiceExecutionSourceInternalApi() {
            @Override public InternalReply<StartIntent> inspect(InternalCall<StartIntentLookup> call) {
                throw new AssertionError("original recovery must not take human route");
            }
            @Override public ExecutionOriginalProtocol.Reply<StartIntent> inspectOriginal(
                    ExecutionOriginalProtocol.Call<StartIntentLookup> call) {
                var reply = bindings.reply(call, new StartIntent(original, IntentState.FROZEN, 1, "MANUAL_OFFLINE",
                        HASH, new OperatorPersonnelFact("person", "assignment", 1, 1, 1, Instant.EPOCH, null),
                        HASH, "command", HASH, "{}", HASH,
                        new SourceContinuity(1, 1, "READY_UNCHANGED", 1L, HASH, null), null));
                return changedTarget ? new ExecutionOriginalProtocol.Reply<>(new ExecutionOriginalProtocol.Binding(1,
                        ServiceExecutionSourceInternalApi.ENDPOINT, "INSPECT_ORIGINAL", original.scope(), "foreign",
                        HASH), reply.requestDigest(), reply.result()) : reply;
            }
        };
    }
}
