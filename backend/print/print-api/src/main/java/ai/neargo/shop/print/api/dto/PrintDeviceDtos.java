package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintDeviceDtos {
    private PrintDeviceDtos() {}

    public record Connection(
            String type,
            String adapterCode,
            String vendorAccountRef,
            String deviceSn,
            String agentNo,
            Map<String, Object> config,
            String physicalRegistrationRef,
            Map<String, String> credentials) {
        public Connection {
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        }
    }

    public record ListAdaptersQuery(String connectionType) {}

    public record AdapterVO(String code, PrintAdapterCapabilities capabilities) {}

    public record ListPrintersQuery(
            String storeNo,
            String status,
            String healthStatus,
            String connectionType,
            Integer page,
            Integer size) {}

    public record PrinterVO(
            String printerNo,
            String storeNo,
            String name,
            String connectionType,
            String adapterCode,
            int paperWidthMm,
            String status,
            String healthStatus,
            long revision) {}

    public record CreatePrinterReq(
            String storeNo, String name, int paperWidthMm, Connection connection) {}

    public record PrinterDetailVO(
            PrinterVO printer,
            long connectionRevision,
            long runtimeRevision,
            String agentNo,
            boolean credentialConfigured,
            long pendingCount,
            String activeJobNo,
            String blockedReason) {}

    public record UpdatePrinterReq(Long expectedRevision, String name, Integer paperWidthMm) {}

    public record ChangeConnectionReq(
            Long expectedRevision, Connection connection, String reason) {}

    public record ChangeStatusReq(Long expectedRevision, String status, String reason) {}

    public record RotateCredentialReq(Long expectedRevision, Map<String, String> credentials) {
        public RotateCredentialReq {
            credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
        }
    }

    public record ProbeReq(Long expectedRevision) {}

    public record TestPrintReq(Long expectedRevision, String testPattern) {}
}
