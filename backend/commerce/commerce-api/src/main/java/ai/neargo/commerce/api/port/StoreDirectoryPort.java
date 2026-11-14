package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

/** Embedded initialization reads newly inserted store facts in the current local transaction. */
public interface StoreDirectoryPort {
 Optional<StoreSnapshot> find(StoreRef store);

}
