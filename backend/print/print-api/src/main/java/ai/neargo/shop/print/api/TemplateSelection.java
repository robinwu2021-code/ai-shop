package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record TemplateSelection(
        TemplateSelectionMode mode, String variantCode, String templateVersionNo) {}
