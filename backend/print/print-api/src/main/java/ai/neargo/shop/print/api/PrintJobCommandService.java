package ai.neargo.shop.print.api;

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

public interface PrintJobCommandService {
    PrintOperation retryJob(String jobNo, RetryJobReq request, PrintActor actor);

    PrintOperation cancelJob(String jobNo, CancelJobReq request, PrintActor actor);

    PrintOperation queryResult(String jobNo, QueryResultReq request, PrintActor actor);

    PrintOperation confirmManually(String jobNo, ConfirmManuallyReq request, PrintActor actor);

    PrintOperation releaseStale(String jobNo, ReleaseStaleReq request, PrintActor actor);

    PrintOperation resolveDeviceUncertainty(
            String printerNo, ResolveDeviceUncertaintyReq request, PrintActor actor);

    PrintMaintenanceReport applyControl(PrintControlFact fact);
}
