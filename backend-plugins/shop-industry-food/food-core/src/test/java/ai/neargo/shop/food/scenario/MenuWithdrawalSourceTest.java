package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.dto.MenuVO;
import ai.neargo.shop.food.dto.MenuWithdrawalSourceContracts;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbConfigRevision;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.ConfigRevisionMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.policy.PublicationDocuments;
import ai.neargo.shop.food.service.impl.MenuWithdrawalSourceServiceImpl;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

/**
 * Real frozen publication projection plus exact native head/action matching; missing owners never
 * become removal.
 */
class MenuWithdrawalSourceTest {
    void run(Consumer<Fixture> action) {
        new ConfigRevisionServiceTest()
                .run(
                        (context, fixture) -> {
                            var json = context.getBean(ObjectMapper.class);
                            var menus = mock(MenuMapper.class);
                            var stores = mock(MenuStoreMapper.class);
                            var revisions = mock(ConfigRevisionMapper.class);
                            var actions = mock(ActionLogMapper.class);
                            var head = new FnbMenu();
                            head.setEntityNo("E");
                            head.setMenuNo("M");
                            head.setName("Menu");
                            head.setMenuType("TIME");
                            head.setTimezone("UTC");
                            head.setPriority(0);
                            head.setStatus("DISABLED");
                            head.setVersion(2L);
                            head.setConfigVersion(1L);
                            when(menus.selectOne(any())).thenReturn(head);
                            when(stores.selectCount(any())).thenReturn(1L);
                            var publication = new FnbConfigRevision();
                            publication.setConfigKind("MENU");
                            publication.setConfigNo("M");
                            publication.setRevisionNo("R1");
                            publication.setRevision(1L);
                            publication.setStatus("EFFECTIVE");
                            publication.setEffectiveAt(
                                    ConfigRevisionServiceTest.NOW.minusDays(1).toLocalDateTime());
                            var document = MenuSourceVerificationTest.document(json, "EXPLICIT");
                            publication.setDefinitionSnapshot(document);
                            publication.setContentHash(PublicationDocuments.hash(json, document));
                            when(revisions.findRevision("E", "R1")).thenReturn(publication);
                            var audit = new FnbActionLog();
                            audit.setActionNo("ACTION");
                            audit.setCreatedAt(
                                    ConfigRevisionServiceTest.NOW
                                            .minusMinutes(1)
                                            .toLocalDateTime());
                            audit.setAfterRef(
                                    FoodCommandFingerprint.hash(
                                            json.writeValueAsString(
                                                    new MenuVO(
                                                            "M",
                                                            "A",
                                                            "Menu",
                                                            "TIME",
                                                            "UTC",
                                                            0,
                                                            null,
                                                            "DISABLED",
                                                            2L,
                                                            1L))));
                            when(actions.selectList(any())).thenReturn(List.of(audit));
                            var service =
                                    new MenuWithdrawalSourceServiceImpl(
                                            menus,
                                            stores,
                                            revisions,
                                            actions,
                                            json,
                                            context.getBean(PlatformTransactionManager.class),
                                            Clock.fixed(
                                                    ConfigRevisionServiceTest.NOW.toInstant(),
                                                    ZoneOffset.UTC));
                            action.accept(
                                    new Fixture(
                                            service,
                                            head,
                                            menus,
                                            stores,
                                            revisions,
                                            actions,
                                            publication));
                        });
    }

    record Fixture(
            MenuWithdrawalSourceServiceImpl service,
            FnbMenu head,
            MenuMapper menus,
            MenuStoreMapper stores,
            ConfigRevisionMapper revisions,
            ActionLogMapper actions,
            FnbConfigRevision publication) {}

    MenuWithdrawalSourceContracts.Query query() {
        return new MenuWithdrawalSourceContracts.Query("E", "A", "M", "R1");
    }

    @Test
    void genuineDisabledHeadWithFrozenOriginalPublicationAndAuditIsDistinctFromActive() {
        run(
                f -> {
                    var proof = f.service().capture(query());
                    assertThat(proof.data().actionNo()).isEqualTo("ACTION");
                    assertThat(proof.data().previousContentHash())
                            .isEqualTo(f.publication().getContentHash());
                    assertThat(f.service().disposition(query()).headStatus()).isEqualTo("DISABLED");
                    f.head().setStatus("DRAFT");
                    assertThat(f.service().disposition(query()).withdrawal()).isNull();
                    assertThatThrownBy(() -> f.service().capture(query()))
                            .isInstanceOf(RuntimeException.class);
                });
    }

    @Test
    void missingOrAmbiguousOriginalDisableActionBlocksWithdrawal() {
        run(
                f -> {
                    when(f.actions().selectList(any())).thenReturn(List.of());
                    assertThatThrownBy(() -> f.service().capture(query()))
                            .isInstanceOf(RuntimeException.class);
                    var a = new FnbActionLog();
                    when(f.actions().selectList(any())).thenReturn(List.of(a, a));
                    assertThatThrownBy(() -> f.service().capture(query()))
                            .isInstanceOf(RuntimeException.class);
                });
    }

    @Test
    void missingScopeUnknownStatusAndCorruptHistoricalPublicationNeverBecomeEmpty() {
        run(
                f -> {
                    f.head().setStatus("UNKNOWN");
                    assertThatThrownBy(() -> f.service().disposition(query()))
                            .isInstanceOf(RuntimeException.class);
                    f.head().setStatus("DISABLED");
                    when(f.stores().selectCount(any())).thenReturn(0L);
                    assertThatThrownBy(() -> f.service().capture(query()))
                            .isInstanceOf(RuntimeException.class);
                    when(f.stores().selectCount(any())).thenReturn(1L);
                    f.publication().setContentHash("f".repeat(64));
                    assertThatThrownBy(() -> f.service().capture(query()))
                            .isInstanceOf(RuntimeException.class);
                });
    }
}
