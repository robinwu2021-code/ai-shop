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

public interface PrintTemplateService {
    PageData<TemplateVO> listTemplates(ListTemplatesQuery query, PrintActor actor);

    PrintOperation createTemplate(CreateTemplateReq request, PrintActor actor);

    PrintOperation createTemplateVersion(
            String templateNo, CreateTemplateVersionReq request, PrintActor actor);

    PrintOperation updateTemplateDraft(
            String versionNo, UpdateTemplateDraftReq request, PrintActor actor);

    PreviewVO previewTemplate(String versionNo, PreviewTemplateReq request, PrintActor actor);

    PrintOperation publishTemplate(String versionNo, PublishTemplateReq request, PrintActor actor);

    PageData<TemplateVersionVO> listTemplateVersions(
            String templateNo, ListTemplateVersionsQuery query, PrintActor actor);

    TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq request, PrintActor actor);
}
