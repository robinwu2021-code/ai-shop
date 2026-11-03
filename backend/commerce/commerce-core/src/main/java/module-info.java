module ai.neargo.commerce.core {
    exports ai.neargo.commerce.core.spi;
    exports ai.neargo.commerce.core.access;
    exports ai.neargo.commerce.core.subscription;
    exports ai.neargo.commerce.core.catalog;
    exports ai.neargo.commerce.core.catalog.business;
    exports ai.neargo.commerce.core.catalog.end;
    exports ai.neargo.commerce.core.catalog.plan;
    exports ai.neargo.commerce.core.catalog.product;

    requires ai.neargo.commerce.api;
}
