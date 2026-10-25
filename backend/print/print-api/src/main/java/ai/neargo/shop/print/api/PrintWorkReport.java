package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintWorkReport(int claimed, int succeeded, int failed, boolean hasMore) {}
