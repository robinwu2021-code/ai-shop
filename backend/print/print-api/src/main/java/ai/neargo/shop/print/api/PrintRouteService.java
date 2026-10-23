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

public interface PrintRouteService {
    PageData<RouteVO> listRoutes(ListRoutesQuery query, PrintActor actor);

    PrintOperation createRoute(CreateRouteReq request, PrintActor actor);

    PrintOperation createRouteVersion(
            String routeNo, CreateRouteVersionReq request, PrintActor actor);

    RouteDryRunVO dryRunRoute(String versionNo, DryRunRouteReq request, PrintActor actor);

    PrintOperation publishRoute(String versionNo, PublishRouteReq request, PrintActor actor);

    PageData<RouteVersionVO> listRouteVersions(
            String routeNo, ListRouteVersionsQuery query, PrintActor actor);
}
