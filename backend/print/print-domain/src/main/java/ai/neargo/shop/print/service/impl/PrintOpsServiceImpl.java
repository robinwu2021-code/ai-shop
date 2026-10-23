package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.monitor.PrintReconcileJob;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintOpsServiceImpl implements PrintOpsService {
    private final PrintReconcileJob reconcile;
    private final PrintMonitorService monitor;
    private final PrintQueryService query;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;

    public PrintOpsServiceImpl(
            PrintReconcileJob r,
            PrintMonitorService m,
            PrintQueryService q,
            PrintOperationService o,
            PrintJsonCodec j) {
        reconcile = r;
        monitor = m;
        query = q;
        ops = o;
        json = j;
    }

    private PrintActor target(PrintOpsActor a, String tenant, String entity, String store) {
        require(
                a.tenantNo().equals(tenant)
                        && a.entityNos().contains(entity)
                        && a.storeNos().contains(store),
                "print.forbidden");
        return new PrintActor(
                tenant, entity, store, "OPS", a.staffNo(), a.idempotencyKey(), a.dataScopes());
    }

    public PrintOperation reconcile(OpsReconcileReq r, PrintOpsActor a) {
        var actor = target(a, r.tenantNo(), r.entityNo(), r.storeNo());
        return ops.execute(
                actor,
                "OPS_PRINT_RECONCILE",
                json.freeze(r).sha256(),
                () ->
                        succeeded(
                                "OPS_PRINT_RECONCILE",
                                "STORE",
                                r.storeNo(),
                                json.freeze(
                                        reconcile.reconcile(
                                                new PrintMaintenanceCommand(
                                                        scope(actor),
                                                        r.sourceKind(),
                                                        r.from(),
                                                        r.to(),
                                                        100,
                                                        r.dryRun())))));
    }

    public PrintOperation getOperation(String no, OpsGetOperationQuery q, PrintOpsActor a) {
        return query.getOperation(no, target(a, q.tenantNo(), q.entityNo(), q.storeNo()));
    }

    public FleetHealthVO getFleetHealth(OpsGetFleetHealthQuery q, PrintOpsActor a) {
        var actor = target(a, q.tenantNo(), q.entityNo(), q.storeNo());
        return new FleetHealthVO(
                List.of(monitor.getHealth(new GetHealthQuery(q.storeNo()), actor)), Instant.now());
    }

    public PageData<IncidentVO> listIncidents(OpsListIncidentsQuery q, PrintOpsActor a) {
        return monitor.listIncidents(
                new ListIncidentsQuery(q.storeNo(), q.status(), null, null, q.page(), q.size()),
                target(a, q.tenantNo(), q.entityNo(), q.storeNo()));
    }
}
