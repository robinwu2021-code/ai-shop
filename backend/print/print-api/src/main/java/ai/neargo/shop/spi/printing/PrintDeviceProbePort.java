package ai.neargo.shop.spi.printing;

import ai.neargo.shop.print.api.*;

public interface PrintDeviceProbePort {
    String adapterCode();

    PrinterProbeResult probe(PrintScope scope, String credentialRef, FrozenJson connection);
}
