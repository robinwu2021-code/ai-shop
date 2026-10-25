package ai.neargo.shop.printing.center.dispatch;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class PrintAdapterRegistry {
    private final Map<String, PrintDispatchPort> adapters;

    public PrintAdapterRegistry(List<PrintDispatchPort> ports) {
        var m = new HashMap<String, PrintDispatchPort>();
        for (var p : ports)
            if (m.putIfAbsent(p.adapterCode(), p) != null)
                throw new IllegalStateException("Duplicate print adapter");
        adapters = Map.copyOf(m);
    }

    public PrintDispatchPort requireCloud(String code) {
        var a = adapters.get(code);
        if (a == null) throw new PrintFailure("print.adapterUnavailable");
        return a;
    }

    public Collection<PrintDispatchPort> all() {
        return adapters.values();
    }
}
