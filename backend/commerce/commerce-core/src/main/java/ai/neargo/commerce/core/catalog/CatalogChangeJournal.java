package ai.neargo.commerce.core.catalog;

import ai.neargo.commerce.api.catalog.IndustryPublicationCommand;
import ai.neargo.commerce.api.catalog.PublicationResult;
import ai.neargo.commerce.api.port.CommerceEvent;
import java.util.Optional;

/** Mall-local configuration history and industry publication receipts. */
public interface CatalogChangeJournal {
    void append(CommerceEvent change, IndustryPublicationCommand command, PublicationResult publication);
    Optional<PublicationResult> publication(String tenant, IndustryPublicationCommand command);
    void finishPending(String tenant);
}
