package ai.neargo.shop.printing.service;

import ai.neargo.shop.spi.printing.PrintPort;

/** Local owner contract keeps transport adapters separate from the durable delivery policy. */
public interface PrintService extends PrintPort { }
