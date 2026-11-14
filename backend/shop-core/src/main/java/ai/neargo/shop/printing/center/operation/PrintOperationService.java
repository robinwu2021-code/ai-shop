package ai.neargo.shop.printing.center.operation;

import ai.neargo.shop.print.api.*;

import java.util.function.Supplier;

public interface PrintOperationService {
    PrintOperation execute(
            PrintActor actor,
            String commandType,
            String requestHash,
            Supplier<PrintOperation> localAction);
}
