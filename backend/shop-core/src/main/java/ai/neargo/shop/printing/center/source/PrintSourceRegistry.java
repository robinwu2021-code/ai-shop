package ai.neargo.shop.printing.center.source;

import ai.neargo.shop.spi.printing.*;

import ai.neargo.shop.print.api.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public final class PrintSourceRegistry {
    private final Map<String, PrintSourceFactsPort> sources;

    public PrintSourceRegistry(List<PrintSourceFactsPort> ports) {
        var m = new HashMap<String, PrintSourceFactsPort>();
        for (var p : ports)
            for (var t : p.sourceTypes())
                if (m.putIfAbsent(t, p) != null)
                    throw new IllegalStateException("Duplicate print source: " + t);
        sources = Map.copyOf(m);
    }

    public PrintSourceFactsPort require(String type) {
        var p = sources.get(type);
        if (p == null) throw new PrintFailure("print.sourceUnavailable");
        return p;
    }

    public Set<String> scenes() {
        return sources.values().stream().flatMap(port -> port.scenes().stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Set<String> sourceTypesForScene(String scene) {
        var types = new TreeSet<String>();
        sources.forEach((type, port) -> { if (port.scenes().contains(scene)) types.add(type); });
        return Set.copyOf(types);
    }

    public List<PrintFactRef> referencesByOrder(PrintScope scope, String no) {
        return sources.values().stream().distinct().flatMap(port -> port.referencesByOrder(scope,no).stream()).distinct().toList();
    }

    public boolean supports(String type, String scene) {
        return sources.containsKey(type) && sources.get(type).scenes().contains(scene);
    }

    public boolean supports(String type) {
        return sources.containsKey(type);
    }
}
