package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record ExportCommand(PrintScope scope, ExportSource source, PdfOutputProfile profile) {}
