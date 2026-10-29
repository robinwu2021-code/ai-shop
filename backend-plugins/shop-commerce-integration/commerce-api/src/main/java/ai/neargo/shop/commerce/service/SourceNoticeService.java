package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Notice;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts.Receipt;

/** Persisting the hint before ACK preserves future effective events across service restarts. */
public interface SourceNoticeService {
    Receipt receive(Notice notice);

    int recoverDue(int limit);
}
