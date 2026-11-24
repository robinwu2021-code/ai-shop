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

public interface PrintExportFacade {
    String INVOICE_PROOF_ARCHIVE_ACTOR = "invoice-proof-archive";
    PrintOperation create(ExportCommand command, PrintActor actor);

    default String archiveInvoiceProof(String jobNo, long revision, PrintActor actor) {
        var scope = new PrintScope(actor.tenantNo(), actor.entityNo(), actor.storeNo());
        var archiveActor = new PrintActor(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                "SYSTEM", INVOICE_PROOF_ARCHIVE_ACTOR, jobNo, actor.dataScopes());
        return create(new ExportCommand(scope, new ExportSource(ExportSourceType.JOB, jobNo, revision),
                PdfOutputProfile.ORIGINAL_ROLL), archiveActor).targetNo();
    }

    PageData<ExportView> page(ExportQuery query, PrintActor actor);

    ExportView get(String exportNo, PrintActor actor);

    DownloadGrant download(String exportNo, PrintActor actor);
}
