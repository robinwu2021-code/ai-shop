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

public interface PrintDeviceService {
    List<AdapterVO> listAdapters(ListAdaptersQuery query, PrintActor actor);

    PageData<PrinterVO> listPrinters(ListPrintersQuery query, PrintActor actor);

    PrintOperation createPrinter(CreatePrinterReq request, PrintActor actor);

    PrinterDetailVO getPrinter(String printerNo, PrintActor actor);

    PrintOperation updatePrinter(String printerNo, UpdatePrinterReq request, PrintActor actor);

    PrintOperation changeConnection(
            String printerNo, ChangeConnectionReq request, PrintActor actor);

    PrintOperation changeStatus(String printerNo, ChangeStatusReq request, PrintActor actor);

    PrintOperation rotateCredential(
            String printerNo, RotateCredentialReq request, PrintActor actor);

    PrintOperation probe(String printerNo, ProbeReq request, PrintActor actor);

    PrintOperation testPrint(String printerNo, TestPrintReq request, PrintActor actor);
}
