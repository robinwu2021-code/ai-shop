package ai.neargo.shop.print.dispatch;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import java.time.*;
import java.util.*;

public interface PrintReceiptService {
    void refreshBatchProjection(PrintScope scope, String batchNo);

    void applyNow(PrintScope scope, String receiptNo);

    ReceiptAck ingest(VerifiedPrintReceipt receipt);

    PrintWorkReport applyPending(PrintWorkerBudget budget);
}
