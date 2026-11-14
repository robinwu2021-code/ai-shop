package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;

/** Immutable observed owner evidence; no remote reads are needed while the appointment root is locked. */
public record HoldEvidence(ResourceSourceAuthorityPort.Binding binding, ResourceBookingPort.Hold hold,
        long arrangementRevision, String arrangementHash) { }
