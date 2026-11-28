package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
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

    public FoodPrintServiceImpl(
            FoodPrintDocumentAssembler documents, Supplier<PrintPort> printing) {
        this.documents = documents;
        this.printing = printing;
    }

    @Override
    public List<JobVO> enqueue(Scope scope, PrintDocumentReq request, String key) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        // 厨房变更、桌台变更、标签由业务事件到达时自动定格并打印（DiningKdsEventConsumer），补打走原任务；
        // 不再按事件号手工首打（厨显事件表退役，TDD §2.8 D-3）。
        if (request != null && Set.of("KITCHEN_CHANGE", "TABLE_CHANGE", "LABEL").contains(request.scene())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var frozen = documents.freeze(scope, request, key);
        var port = printing.get();
        if (port == null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return port.enqueue(scope, frozen);
    }
}
