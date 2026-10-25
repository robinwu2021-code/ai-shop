package ai.neargo.shop.printing.center.dispatch;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import java.time.*;
import java.util.*;

public interface PrintReceiptService {
    void refreshBatchProjection(PrintScope scope, String batchNo);

    void applyNow(PrintScope scope, String receiptNo);

    ReceiptAck ingest(VerifiedPrintReceipt receipt);

    PrintWorkReport applyPending(PrintWorkerBudget budget);
}
