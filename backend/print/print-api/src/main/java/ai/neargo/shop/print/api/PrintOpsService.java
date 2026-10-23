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

public interface PrintOpsService {
    PrintOperation reconcile(OpsReconcileReq request, PrintOpsActor actor);

    PrintOperation getOperation(
            String operationNo, OpsGetOperationQuery query, PrintOpsActor actor);

    FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery query, PrintOpsActor actor);

    PageData<IncidentVO> listIncidents(OpsListIncidentsQuery query, PrintOpsActor actor);
}
