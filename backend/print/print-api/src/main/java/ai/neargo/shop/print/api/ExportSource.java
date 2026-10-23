package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record ExportSource(ExportSourceType type, String no, long expectedRevision) {}
