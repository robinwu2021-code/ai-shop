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

public interface PrintMonitorService {
    GetHealthVO getHealth(GetHealthQuery query, PrintActor actor);

    PageData<IncidentVO> listIncidents(ListIncidentsQuery query, PrintActor actor);

    PrintOperation acknowledgeIncident(
            String incidentNo, AcknowledgeIncidentReq request, PrintActor actor);

    PageData<ActionLogVO> listActionLogs(ListActionLogsQuery query, PrintActor actor);

    ChangePageVO listChanges(ListChangesQuery query, PrintActor actor);
}
