package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintIntent(
        PrintFactRef factRef, PrintMode printMode, PrintOutputOptions outputOptions) {}
