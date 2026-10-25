package ai.neargo.shop.print.api.dto;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintDeviceDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.math.*;
import java.time.*;
import java.util.*;

public final class PrintSimulationDtos {
    private PrintSimulationDtos() {}

    public record SimulationReq(
            String storeNo,
            String scene,
            SimulationSource source,
            String variantCode,
            SimulationFixtureCode fixtureCode,
            TemplateSelection templateSelection,
            PrintOutputOptions outputOptions) {}

    public record SimulationQuery(
            String storeNo, String scene, String status, Integer page, Integer size) {}

    public record SimulationView(
            String simulationNo,
            String scene,
            String status,
            String fixtureCode,
            FrozenJson expectedResults,
            FrozenJson actualResults,
            FrozenJson eventJournal,
            long revision) {}
}
