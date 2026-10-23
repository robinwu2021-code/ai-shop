package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintExportDtos {
    private PrintExportDtos() {}

    public record ExportReq(String storeNo, ExportSource source, PdfOutputProfile profile) {}

    public record ExportQuery(
            String storeNo,
            String sourceType,
            String sourceNo,
            String status,
            Integer page,
            Integer size) {}

    public record ExportView(
            String exportNo,
            String sourceType,
            String sourceNo,
            String status,
            long fileSize,
            int pageCount,
            Instant expiresAt,
            long revision) {}
}
