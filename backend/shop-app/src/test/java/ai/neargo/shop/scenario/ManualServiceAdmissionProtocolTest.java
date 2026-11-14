package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualAdmissionView;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualLookup;
import ai.neargo.shop.svc.InternalBindings;

import org.junit.jupiter.api.Test;

/** Exact scope/action/original request binding and disabled-secret denial. */
class ManualServiceAdmissionProtocolTest {
    @Test
    void currentScopeAndOriginalRequestAreBound() {
        var bindings = new InternalBindings("task64-test-binding-key");
        var request = call("S", "original-key");
        var reply =
                bindings.reply(
                        request,
                        new ManualAdmissionView(
                                new OperationRef("BEAUTY", "OP"),
                                "O",
                                "SO",
                                0,
                                0,
                                "AWAITING_RECEIPT",
                                null,
                                null,
                                null,
                                null,
                                "CNY",
                                null,
                                null,
                                "AWAITING_RECEIPT",
                                null));
        bindings.require(request, reply);
        assertThatThrownBy(() -> bindings.require(call("T", "original-key"), reply))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> bindings.require(call("S", "changed-key"), reply))
                .isInstanceOf(RuntimeException.class);
        assertThat(bindings.digest(request)).hasSize(64);
    }

    @Test
    void defaultDisabledOpsHaveNoPermissiveProvider() {
        assertThatThrownBy(() -> new InternalBindings("").digest(call("S", "key")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new ManualLookup(new OperationRef("BEAUTY", "OP"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    InternalCall<Scoped<ManualLookup>> call(String store, String key) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        ManualServiceAdmissionInternalApi.ENDPOINT,
                        "QUERY",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                        "E",
                        store,
                        "OP"),
                new Scoped<>(
                        new CheckoutScope("MAIN", "E", store, "A"),
                        new ManualLookup(new OperationRef("BEAUTY", "OP"), null, key)));
    }
}
