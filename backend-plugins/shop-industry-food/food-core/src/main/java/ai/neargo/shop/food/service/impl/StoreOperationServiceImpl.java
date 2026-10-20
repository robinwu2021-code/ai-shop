package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.ConditionalOnFoodEnabled;
import ai.neargo.shop.food.config.FoodPersistenceConfiguration.PersistenceEnabled;
import ai.neargo.shop.food.dto.GetPolicyQuery;
import ai.neargo.shop.food.dto.StoreOperationPolicyResultVO;
import ai.neargo.shop.food.entity.FnbStorePolicy;
import ai.neargo.shop.food.mapper.FoodMappers.StorePolicyMapper;
import ai.neargo.shop.food.service.StoreOperationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Reads policy history without seeding defaults, changing store hours or granting transaction capabilities. */
@Service
@ConditionalOnFoodEnabled
@Conditional(PersistenceEnabled.class)
public class StoreOperationServiceImpl implements StoreOperationService {
    private final StorePolicyMapper mapper;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public StoreOperationServiceImpl(StorePolicyMapper mapper, ObjectMapper json) {
        this(mapper, json, Clock.systemUTC());
    }

    /** Explicit clock keeps effective-time selection deterministic without changing platform time settings. */
    public StoreOperationServiceImpl(StorePolicyMapper mapper, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(transactionManager = "transactionManager", readOnly = true)
    public StoreOperationPolicyResultVO getPolicy(String entityNo, String storeNo, GetPolicyQuery query) {
        if (entityNo == null || entityNo.isBlank() || storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var at = query == null || query.at() == null ? clock.instant() : query.at();
        var rows = mapper.selectList(Wrappers.<FnbStorePolicy>lambdaQuery()
                .eq(FnbStorePolicy::getEntityNo, entityNo).eq(FnbStorePolicy::getStoreNo, storeNo)
                .le(FnbStorePolicy::getEffectiveFrom, LocalDateTime.ofInstant(at, ZoneOffset.UTC))
                .orderByDesc(FnbStorePolicy::getEffectiveFrom).last("LIMIT 1"));
        if (rows.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        FnbStorePolicy row = rows.getFirst();
        try {
            ZoneId.of(row.getTimezone());
            if (row.getCutoverMinute() == null || row.getCutoverMinute() < 0 || row.getCutoverMinute() > 1439
                    || !Set.of("PREPAY", "POSTPAY").contains(row.getPayPolicy())
                    || row.getVersion() == null || row.getVersion() < 0) {
                throw new IllegalArgumentException("Invalid policy snapshot");
            }
            var capabilities = json.readTree(row.getCapabilities());
            if (capabilities == null || !capabilities.isArray()) {
                throw new IllegalArgumentException("Invalid capabilities");
            }
            var names = new ArrayList<String>();
            for (var item : capabilities) {
                if (!item.isTextual() || item.asString().isBlank()) {
                    throw new IllegalArgumentException("Invalid capability");
                }
                names.add(item.asString());
            }
            return new StoreOperationPolicyResultVO(row.getPolicyNo(), row.getTimezone(), row.getCutoverMinute(),
                    row.getPayPolicy(), names, row.getVersion());
        } catch (RuntimeException invalidSnapshot) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
}
