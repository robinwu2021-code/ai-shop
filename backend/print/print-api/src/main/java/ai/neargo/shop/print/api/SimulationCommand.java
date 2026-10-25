package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record SimulationCommand(
        PrintScope scope,
        String scene,
        SimulationSource source,
        String variantCode,
        SimulationFixtureCode fixtureCode,
        TemplateSelection templateSelection,
        PrintOutputOptions outputOptions) {}
