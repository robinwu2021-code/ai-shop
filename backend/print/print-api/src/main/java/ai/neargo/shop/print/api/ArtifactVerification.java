package ai.neargo.shop.print.api;

import java.math.*;
import java.time.*;
import java.util.*;

public record ArtifactVerification(boolean exists, String objectKey, long size, String sha256) {}
