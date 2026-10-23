package ai.neargo.shop.print.service.impl;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.dispatch.PrintReceiptService;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PrintCallbackServiceImpl implements PrintCallbackService {
    private final Map<String, PrintCallbackVerifierPort> verifiers;
    private final PrintReceiptService receipts;

    public PrintCallbackServiceImpl(List<PrintCallbackVerifierPort> ports, PrintReceiptService r) {
        var map = new HashMap<String, PrintCallbackVerifierPort>();
        for (var p : ports)
            if (map.putIfAbsent(p.adapterCode(), p) != null)
                throw new IllegalStateException("Duplicate print callback verifier");
        verifiers = Map.copyOf(map);
        receipts = r;
    }

    public String receive(RawPrintCallback callback) {
        if (callback.body().length > 1024 * 1024) throw new PrintFailure("print.callbackTooLarge");
        var verifier = verifiers.get(callback.adapterCode());
        if (verifier == null) throw new PrintFailure("print.callbackUnsupported");
        var verified = verifier.verify(callback);
        receipts.ingest(verified);
        return verifier.successAcknowledgement();
    }
}
