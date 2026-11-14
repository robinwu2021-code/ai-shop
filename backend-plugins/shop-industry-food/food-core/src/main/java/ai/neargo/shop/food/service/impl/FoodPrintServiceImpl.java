package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.assembler.FoodEventPrintDocumentAssembler;
import ai.neargo.shop.food.assembler.FoodPrintDocumentAssembler;
import ai.neargo.shop.food.dto.PrintDocumentReq;
import ai.neargo.shop.food.service.FoodPrintService;
import ai.neargo.shop.spi.printing.PrintPort;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.Scope;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Source reads finish before remote shared-print calls, so network uncertainty never holds food row
 * locks.
 */
public class FoodPrintServiceImpl implements FoodPrintService {
    private final FoodPrintDocumentAssembler documents;
    private final Supplier<PrintPort> printing;
    private final FoodEventPrintDocumentAssembler eventDocuments;

    public FoodPrintServiceImpl(
            FoodPrintDocumentAssembler documents, Supplier<PrintPort> printing) {
        this(documents, printing, null);
    }

    public FoodPrintServiceImpl(
            FoodPrintDocumentAssembler documents,
            Supplier<PrintPort> printing,
            FoodEventPrintDocumentAssembler eventDocuments) {
        this.documents = documents;
        this.printing = printing;
        this.eventDocuments = eventDocuments;
    }

    @Override
    public List<JobVO> enqueue(Scope scope, PrintDocumentReq request, String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var frozen =
                request != null
                                && Set.of("KITCHEN_CHANGE", "TABLE_CHANGE", "LABEL")
                                        .contains(request.scene())
                        ? eventDocument(scope, request, key)
                        : documents.freeze(scope, request, key);
        var port = printing.get();
        if (port == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return port.enqueue(scope, frozen);
    }

    private Enqueue eventDocument(Scope scope, PrintDocumentReq request, String key) {
        if (eventDocuments == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return eventDocuments.freeze(scope, request, key);
    }
}
