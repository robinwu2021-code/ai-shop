package ai.neargo.shop.print.api;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public interface PrintQueryService {
    PrintBatchVO getBatch(String batchNo, PrintActor actor);

    PageData<PrintJobVO> listJobs(ListJobsQuery query, PrintActor actor);

    PrintJobView getJob(PrintJobRef ref, PrintActor actor);

    PageData<PrintAttemptVO> listAttempts(String jobNo, ListAttemptsQuery query, PrintActor actor);

    PageData<PrintReceiptVO> listReceipts(String jobNo, ListReceiptsQuery query, PrintActor actor);

    DocumentPreviewVO getDocument(String jobNo, PrintActor actor);

    OrderPrintSummaryVO getOrderSummary(
            String orderNo, GetOrderSummaryQuery query, PrintActor actor);

    PrintOperation getOperation(String operationNo, PrintActor actor);
}
