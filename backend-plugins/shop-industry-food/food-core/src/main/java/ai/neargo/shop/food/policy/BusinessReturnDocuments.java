package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.BusinessReturnContracts;
import ai.neargo.shop.food.dto.BusinessReturnContracts.Frozen;
import ai.neargo.shop.food.entity.FnbReturn;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Business sources are deliberately incompatible with the legacy financial command snapshot. */
public final class BusinessReturnDocuments {
    private BusinessReturnDocuments() {}

    public static Frozen read(FnbReturn row, ObjectMapper json) {
        if (row == null
                || !"NOT_REQUESTED".equals(row.getFinancialStatus())
                || row.getCoreCaseRef() != null
                || row.getRequestSnapshot() == null) throw conflict();
        try {
            var frozen = json.readValue(row.getRequestSnapshot(), Frozen.class);
            if (frozen == null
                    || !BusinessReturnContracts.KIND.equals(frozen.kind())
                    || frozen.schema() != 1
                    || !Objects.equals(row.getEntityNo(), frozen.entityNo())
                    || !Objects.equals(row.getStoreNo(), frozen.storeNo())
                    || !Objects.equals(row.getBatchNo(), frozen.batchNo())
                    || !Objects.equals(row.getBuyerNo(), frozen.buyerNo())
                    || !Objects.equals(row.getCreatedBy(), frozen.actorNo())
                    || !Objects.equals(row.getReason(), frozen.reason())
                    || frozen.sourceHash() == null
                    || !frozen.sourceHash().matches("[0-9a-f]{64}")
                    || frozen.ownerRead() == null
                    || frozen.ownerRead().original() == null
                    || frozen.items().isEmpty()
                    || frozen.items().size() > 256
                    || !Set.of(
                                    "BUSINESS_REQUESTED",
                                    "CANCELLED",
                                    "APPROVAL_PENDING",
                                    "APPROVAL_UNKNOWN",
                                    "APPROVED")
                            .contains(row.getStatus())) {
                throw conflict();
            }
            var seen = new HashSet<String>();
            for (var item : frozen.items()) {
                if (item == null
                        || item.foodLineNo() == null
                        || !seen.add(item.foodLineNo())
                        || item.mainAllocationNo() == null
                        || item.mainOrderItemId() == null
                        || item.components().isEmpty()) {
                    throw conflict();
                }
                KitchenQuantityPolicy.positive(item.qty());
                KitchenQuantityPolicy.positive(item.originalQty());
                if (item.qty().compareTo(item.originalQty()) > 0) throw conflict();
            }
            return frozen;
        } catch (JacksonException malformed) {
            throw conflict();
        }
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.CONFLICT);
    }
}
