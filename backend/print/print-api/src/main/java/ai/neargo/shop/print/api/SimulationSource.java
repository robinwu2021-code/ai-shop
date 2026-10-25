package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record SimulationSource(
        SimulationSourceType type, String no, PrintFactRef factRef, Long expectedRevision) {}
