package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.dto.TaxSourceNoticeContracts;

/**
 * Durable platform/merchant tax-source fanout operates on original CIM scopes, never an invented
 * merchant.
 */
public interface TaxSourceNoticeService {
    SourceNoticeContracts.Receipt receive(TaxSourceNoticeContracts.Notice notice);
}
