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

public interface PrintDispatchPort {
    String adapterCode();

    PrintAdapterCapabilities capabilities();

    DispatchResult submit(PrintDispatch dispatch);

    QueryResult query(PrintAttemptRef ref);

    CancelResult cancel(PrintAttemptRef ref);
}
