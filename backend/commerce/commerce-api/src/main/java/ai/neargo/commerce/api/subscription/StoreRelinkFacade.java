package ai.neargo.commerce.api.subscription;

import ai.neargo.commerce.api.common.StoreRef;

/** Read evidence and trusted FREE-only relink inside a host claim transaction. */
public interface StoreRelinkFacade {
    record Command(StoreRef store, String expectedEntityNo, String targetEntityNo, String changeNo) {}
    record Facts(boolean complete, boolean hasPaidHistory, boolean accountPresent) {
        public Facts(boolean complete, boolean hasPaidHistory) { this(complete, hasPaidHistory, false); }
    }
    Facts inspect(StoreRef store);
    void relinkFree(Command command);
}
