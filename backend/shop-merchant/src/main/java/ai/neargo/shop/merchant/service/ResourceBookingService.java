package ai.neargo.shop.merchant.service;

import ai.neargo.shop.merchant.service.ResourceCommandService.Receipt;
import ai.neargo.shop.merchant.service.ResourceCommandService.Scope;

/** Owner-local executor: a durable accepted command is the only input to capacity mutations. */
public interface ResourceBookingService {
    /** Source IO precedes a short atomic transaction; retries return the immutable original command receipt. */
    Receipt execute(Scope scope, String commandNo);
}
