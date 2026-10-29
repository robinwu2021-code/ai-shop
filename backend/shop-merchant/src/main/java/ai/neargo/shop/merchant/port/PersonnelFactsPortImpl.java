package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelPositionMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelSkillMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PersonnelStoreMapper;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads one bounded owner snapshot without exporting login identities, private contact data or resource promises. */
@Component
public class PersonnelFactsPortImpl implements PersonnelFactsPort {
    private final PersonnelStoreMapper stores;
    private final PersonnelPositionMapper positions;
    private final PersonnelSkillMapper skills;
    private final TransactionTemplate snapshot;

    public PersonnelFactsPortImpl(PersonnelStoreMapper stores, PersonnelPositionMapper positions,
            PersonnelSkillMapper skills, PlatformTransactionManager transactionManager) {
        this.stores = stores;
        this.positions = positions;
        this.skills = skills;
        snapshot = new TransactionTemplate(transactionManager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public List<PersonnelFact> getBatch(Query query) {
        Objects.requireNonNull(query, "query");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("read personnel facts outside caller transactions");
        }
        return DataScopeContext.executeWithoutScope(() -> snapshot.execute(status -> load(query)));
    }

    private List<PersonnelFact> load(Query query) {
        LocalDateTime at = LocalDateTime.ofInstant(query.at(), ZoneOffset.UTC);
        var assignments = stores.selectEffectiveAssignments(query.tenantNo(), query.entityNo(), query.storeNo(),
                query.personnelNos(), at);
        if (assignments.size() > 200) {
            throw new IllegalStateException("too many effective assignments");
        }
        if (assignments.isEmpty()) {
            return List.of();
        }
        var people = new HashSet<String>();
        for (var row : assignments) {
            if (!people.add(row.personnelNo())) {
                throw new IllegalStateException("ambiguous effective employment");
            }
        }
        var assignmentNos = assignments.stream().map(row -> row.assignmentNo()).toList();
        var positionRows = positions.selectByAssignments(query.tenantNo(), query.entityNo(), query.storeNo(),
                assignmentNos);
        var skillRows = skills.selectEffectiveByPersonnel(query.tenantNo(), query.entityNo(), query.storeNo(),
                people, at);
        if (positionRows.size() > 2000 || skillRows.size() > 2000) {
            throw new IllegalStateException("too many personnel child facts");
        }
        Map<String, TreeSet<String>> positionCodes = new HashMap<>();
        for (var row : positionRows) {
            positionCodes.computeIfAbsent(row.assignmentNo(), key -> new TreeSet<>()).add(row.positionCode());
        }
        Map<String, List<Qualification>> qualifications = new HashMap<>();
        for (var row : skillRows) {
            qualifications.computeIfAbsent(row.personnelNo(), key -> new ArrayList<>()).add(new Qualification(
                    row.skillNo(), row.storeNo(), row.goodsNo(), row.skuNo(), row.levelCode(),
                    PersonnelFactsPortImpl.utc(row.validFrom()), PersonnelFactsPortImpl.utc(row.validTo())));
        }
        List<PersonnelFact> result = new ArrayList<>();
        for (var row : assignments) {
            result.add(new PersonnelFact(row.personnelNo(), row.assignmentNo(), query.storeNo(), row.displayName(),
                    row.levelCode(), row.publicVisible(), PersonnelFactsPortImpl.utc(row.validFrom()),
                    PersonnelFactsPortImpl.utc(row.validTo()),
                    new ArrayList<>(positionCodes.getOrDefault(row.assignmentNo(), new TreeSet<>())),
                    qualifications.getOrDefault(row.personnelNo(), List.of())));
        }
        return List.copyOf(result);
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
