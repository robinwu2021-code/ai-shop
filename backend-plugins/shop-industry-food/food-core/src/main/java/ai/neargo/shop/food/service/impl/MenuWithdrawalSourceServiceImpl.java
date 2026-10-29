package ai.neargo.shop.food.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.ConfigRevisionContentVO;
import ai.neargo.shop.food.dto.ConfigRevisionVO;
import ai.neargo.shop.food.dto.MenuSourceContracts;
import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.MenuWithdrawalSourceContracts;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.MenuSourcePolicy;
import ai.neargo.shop.food.service.MenuWithdrawalSourceService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Original FOOD-only RR read binds head, store membership, immutable publication and matching
 * disable audit.
 */
public final class MenuWithdrawalSourceServiceImpl implements MenuWithdrawalSourceService {
    private final MenuMapper menus;
    private final MenuStoreMapper stores;
    private final ConfigRevisionMapper revisions;
    private final ActionLogMapper actions;
    private final ObjectMapper json;
    private final TransactionTemplate read;
    private final Clock clock;

    public MenuWithdrawalSourceServiceImpl(
            MenuMapper menus,
            MenuStoreMapper stores,
            ConfigRevisionMapper revisions,
            ActionLogMapper actions,
            ObjectMapper json,
            PlatformTransactionManager manager,
            Clock clock) {
        this.menus = menus;
        this.stores = stores;
        this.revisions = revisions;
        this.actions = actions;
        this.json = json;
        this.clock = clock;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public MenuWithdrawalSourceContracts.Disposition disposition(
            MenuWithdrawalSourceContracts.Query q) {
        if (q == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var original =
                new MenuSourceContracts.Query(
                        q.entityNo(),
                        q.storeNo(),
                        q.menuNo(),
                        q.previousRevision(),
                        MenuSourceContracts.Kind.MENU,
                        q.menuNo(),
                        null,
                        null,
                        MenuSourceContracts.Mode.HISTORICAL);
        MenuSourcePolicy.validateQuery(original);
        return read.execute(
                tx -> {
                    var head =
                            menus.selectOne(
                                    Wrappers.<FnbMenu>lambdaQuery()
                                            .eq(FnbMenu::getEntityNo, q.entityNo())
                                            .eq(FnbMenu::getMenuNo, q.menuNo()));
                    require(
                            head != null
                                    && head.getVersion() != null
                                    && head.getVersion() >= 0
                                    && Set.of("DRAFT", "PUBLISHED", "DISABLED")
                                            .contains(head.getStatus()));
                    require(
                            stores.selectCount(
                                            Wrappers.<FnbMenuStore>lambdaQuery()
                                                    .eq(FnbMenuStore::getEntityNo, q.entityNo())
                                                    .eq(FnbMenuStore::getMenuNo, q.menuNo())
                                                    .eq(FnbMenuStore::getStoreNo, q.storeNo()))
                                    == 1);
                    var proof = "DISABLED".equals(head.getStatus()) ? capture(q) : null;
                    var now = clock.instant();
                    return new MenuWithdrawalSourceContracts.Disposition(
                            q,
                            head.getStatus(),
                            head.getVersion(),
                            proof,
                            now,
                            now.plusSeconds(30),
                            PricingSnapshotHash.of(
                                    Arrays.asList(q, head.getStatus(), head.getVersion(), now)));
                });
    }

    @Override
    public MenuWithdrawalSourceContracts.Proof capture(MenuWithdrawalSourceContracts.Query q) {
        if (q == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var originalQuery =
                new MenuSourceContracts.Query(
                        q.entityNo(),
                        q.storeNo(),
                        q.menuNo(),
                        q.previousRevision(),
                        MenuSourceContracts.Kind.MENU,
                        q.menuNo(),
                        null,
                        null,
                        MenuSourceContracts.Mode.HISTORICAL);
        MenuSourcePolicy.validateQuery(originalQuery);
        return read.execute(
                tx -> {
                    var head =
                            menus.selectOne(
                                    Wrappers.<FnbMenu>lambdaQuery()
                                            .eq(FnbMenu::getEntityNo, q.entityNo())
                                            .eq(FnbMenu::getMenuNo, q.menuNo()));
                    require(
                            head != null
                                    && "DISABLED".equals(head.getStatus())
                                    && head.getVersion() != null
                                    && head.getVersion() >= 0);
                    require(
                            stores.selectCount(
                                            Wrappers.<FnbMenuStore>lambdaQuery()
                                                    .eq(FnbMenuStore::getEntityNo, q.entityNo())
                                                    .eq(FnbMenuStore::getMenuNo, q.menuNo())
                                                    .eq(FnbMenuStore::getStoreNo, q.storeNo()))
                                    == 1);
                    var publication = revisions.findRevision(q.entityNo(), q.previousRevision());
                    require(
                            publication != null
                                    && q.menuNo().equals(publication.getConfigNo())
                                    && "MENU".equals(publication.getConfigKind())
                                    && !"CANCELLED".equals(publication.getStatus())
                                    && publication.getEffectiveAt() != null
                                    && !publication
                                            .getEffectiveAt()
                                            .toInstant(ZoneOffset.UTC)
                                            .isAfter(clock.instant()));
                    var frozen =
                            new ConfigRevisionContentVO(
                                    new ConfigRevisionVO(
                                            publication.getRevisionNo(),
                                            "MENU",
                                            q.menuNo(),
                                            publication.getRevision(),
                                            publication.getEffectiveAt().atOffset(ZoneOffset.UTC),
                                            publication.getContentHash(),
                                            "EFFECTIVE"),
                                    publication.getDefinitionSnapshot());
                    MenuSourcePolicy.project(json, originalQuery, frozen);
                    var view =
                            new MenuVO(
                                    head.getMenuNo(),
                                    q.storeNo(),
                                    head.getName(),
                                    head.getMenuType(),
                                    head.getTimezone(),
                                    head.getPriority(),
                                    head.getExclusiveGroup(),
                                    head.getStatus(),
                                    head.getVersion(),
                                    head.getConfigVersion());
                    String after = FoodCommandFingerprint.hash(json.writeValueAsString(view));
                    var found =
                            actions.selectList(
                                    Wrappers.<FnbActionLog>lambdaQuery()
                                            .eq(FnbActionLog::getEntityNo, q.entityNo())
                                            .eq(FnbActionLog::getStoreNo, q.storeNo())
                                            .eq(FnbActionLog::getObjectType, "MENU")
                                            .eq(FnbActionLog::getObjectNo, q.menuNo())
                                            .eq(FnbActionLog::getAction, "DISABLE_MENU")
                                            .eq(FnbActionLog::getAfterRef, after)
                                            .orderByDesc(FnbActionLog::getId)
                                            .last("LIMIT 2"));
                    require(found.size() == 1 && found.getFirst().getCreatedAt() != null);
                    var action = found.getFirst();
                    var data =
                            new MenuWithdrawalSourceContracts.Data(
                                    q,
                                    head.getVersion(),
                                    publication.getContentHash(),
                                    action.getActionNo(),
                                    after,
                                    action.getCreatedAt().toInstant(ZoneOffset.UTC));
                    var now = clock.instant();
                    require(!data.withdrawnAt().isAfter(now));
                    return new MenuWithdrawalSourceContracts.Proof(
                            data,
                            now,
                            now.plusSeconds(30),
                            PricingSnapshotHash.of(List.of(data, now)));
                });
    }

    private static void require(boolean value) {
        if (!value) throw BizException.of(ErrorCode.CONFLICT);
    }
}
