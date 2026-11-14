package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.commerce.mapper.CommerceMappers.OrderLinkMapper;
import ai.neargo.shop.commerce.service.ChannelFulfillmentService;
import ai.neargo.shop.commerce.service.impl.ChannelFulfillmentTriggerPortImpl;
import ai.neargo.shop.commerce.service.impl.CommerceCommands;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentTriggerPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicReference;

class ChannelFulfillmentTriggerTest {
    @Test
    void
            retriesUntilActualScopedImportConfirmsAndUsesOneStableOriginalEventKeyOutsideTheReadTransaction() {
        new ChannelOrderMaintenanceTest()
                .run(
                        new ChannelOrderMaintenanceTest.Gateway(),
                        s -> {
                            var actual = mock(ChannelFulfillmentService.class);
                            var key = new AtomicReference<String>();
                            when(actual.publishFulfillment(
                                            anyString(),
                                            anyString(),
                                            anyString(),
                                            any(),
                                            anyString()))
                                    .thenAnswer(
                                            call -> {
                                                assertThat(
                                                                TransactionSynchronizationManager
                                                                        .isActualTransactionActive())
                                                        .isFalse();
                                                assertThat((String) call.getArgument(0))
                                                        .isEqualTo("E");
                                                assertThat((String) call.getArgument(1))
                                                        .isEqualTo("S");
                                                assertThat((String) call.getArgument(2))
                                                        .isEqualTo("SYSTEM");
                                                String current = call.getArgument(4);
                                                if (key.get() == null) key.set(current);
                                                else assertThat(current).isEqualTo(key.get());
                                                return new OperationVO(
                                                        "PUBLICATION",
                                                        "FULFILLMENT_PUBLISH",
                                                        "QUEUED",
                                                        "ACC",
                                                        null,
                                                        null,
                                                        null,
                                                        0);
                                            });
                            var trigger =
                                    new ChannelFulfillmentTriggerPortImpl(
                                            s.context().getBean(OrderLinkMapper.class),
                                            s.context().getBean(CommerceCommands.class),
                                            actual);
                            var request =
                                    new ChannelFulfillmentTriggerPort.Request(
                                            "E",
                                            "S",
                                            Owner.FOOD,
                                            "IMPORT",
                                            "BATCH",
                                            "CORE",
                                            "HANDOVER",
                                            "CUSTODY_V1:" + "a".repeat(64),
                                            "ORIGINAL_SERVICE_EVENT");
                            assertThat(trigger.record(request).status())
                                    .isEqualTo(ChannelFulfillmentTriggerPort.Status.WAITING_IMPORT);
                            verifyNoInteractions(actual);
                            s.sql()
                                    .execute(
                                            "UPDATE cim_order_link SET"
                                                + " import_ref='IMPORT',source_order_ref='BATCH',core_order_ref='CORE',import_status='CONFIRMED',acceptance_status='CONFIRMED'");
                            assertThat(trigger.record(request).status())
                                    .isEqualTo(ChannelFulfillmentTriggerPort.Status.RECORDED);
                            assertThat(trigger.record(request).operationNo())
                                    .isEqualTo("PUBLICATION");
                            assertThatThrownBy(
                                            () ->
                                                    trigger.record(
                                                            new ChannelFulfillmentTriggerPort
                                                                    .Request(
                                                                    "E",
                                                                    "S",
                                                                    Owner.FOOD,
                                                                    "IMPORT",
                                                                    "BATCH",
                                                                    "FOREIGN",
                                                                    "HANDOVER",
                                                                    request.sourceRevision(),
                                                                    request.originalEventNo())))
                                    .isInstanceOf(RuntimeException.class);
                        });
    }
}
