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

public interface PrinterLeaseService {
    PreparedPrintAttempt prepare(PrintScope scope, String printerNo, String workerNo);

    void releaseIfCurrent(PrintScope scope, String printerNo, String attemptNo, long fenceEpoch);
}
