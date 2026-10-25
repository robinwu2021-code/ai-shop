package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record PrintSourceReference(
        String type, String no, String factNo, String revision, String scene) {}
