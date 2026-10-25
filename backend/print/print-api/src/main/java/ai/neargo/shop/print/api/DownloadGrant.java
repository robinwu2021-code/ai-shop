package ai.neargo.shop.print.api;

import java.math.*;
import java.net.URI;
import java.time.*;
import java.util.*;

public record DownloadGrant(URI url, Instant expiresAt, String fileName, String contentType) {}
