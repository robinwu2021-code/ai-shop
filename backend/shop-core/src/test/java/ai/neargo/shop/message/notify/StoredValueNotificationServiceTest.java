package ai.neargo.shop.message.notify;

import ai.neargo.shop.message.MessageService;
import ai.neargo.shop.message.entity.StoredValueNotifyTask;
import ai.neargo.shop.message.mapper.StoredValueNotifyTaskMapper;
import ai.neargo.shop.message.notify.impl.StoredValueNotificationServiceImpl;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import ai.neargo.shop.spi.notify.WxSubscribePort.WxSubscribeException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredValueNotificationServiceTest {
    private final StoredValueNotifyTaskMapper tasks = mock(StoredValueNotifyTaskMapper.class);
    private final MessageService messages = mock(MessageService.class);
    private final WxSubscribeSender sender = mock(WxSubscribeSender.class);
    private final WxSubscribePort port = mock(WxSubscribePort.class);
    private final SceneChannelRouting routing = mock(SceneChannelRouting.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final List<String> states = new ArrayList<>();
    private StoredValueNotificationServiceImpl service;

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                StoredValueNotifyTask.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(port.configuredForApp(any())).thenReturn(true);
        when(port.templateId(any())).thenReturn("REAL_TEMPLATE");
        when(routing.enabled(any(), any(), any())).thenReturn(true);
        when(tasks.update(eq(null), any(Wrapper.class))).thenAnswer(call -> {
            LambdaUpdateWrapper<StoredValueNotifyTask> update = call.getArgument(1);
            var status = java.util.regex.Pattern.compile("status=.{2}ew.paramNameValuePairs.([^}]+).")
                    .matcher(update.getSqlSet());
            // Match only the SET status value, never the WHERE clause's prior state.
            if (status.find()) {
                states.add(String.valueOf(update.getParamNameValuePairs().get(status.group(1))));
            }
            return 1;
        });
        service = new StoredValueNotificationServiceImpl(tasks, messages, sender, port, routing,
                new ObjectMapper(), manager);
    }

    private StoredValueNotifyTask task(int attempts) {
        var task = new StoredValueNotifyTask();
        task.setId(1L);
        task.setOpenId("real-open-id");
        task.setTemplateId("REAL_TEMPLATE");
        task.setStoreNo("S1");
        task.setDocumentNo("DC1");
        task.setValuesJson("{}");
        task.setAttempts(attempts);
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of(task));
        return task;
    }

    @Test
    void duplicateBusinessDeliveryDoesNotReserveAuthorizationAgain() {
        when(tasks.insertOnce(any())).thenReturn(0);
        service.enqueue("MAIN", "NEW_EVENT", "US1", "U1", "S1", "DC1", Map.of());
        verify(sender, never()).reserve(any(), any());
        verify(messages, never()).push(any(), any(), any(), any(), any(), any());
    }

    @Test
    void networkUncertaintyIsUnknownAndStaleSendingRecoveryAlsoNeverSendsAgain() {
        task(0);
        doThrow(new WxSubscribeException("timeout", false, true)).when(port).sendFielded(any(), any(), any(), any());
        service.dispatchDue();
        assertThat(states).containsExactly("UNKNOWN", "SENDING", "UNKNOWN");
    }

    @Test
    void staleSendingTasksAreMarkedUnknownWithoutExternalReplay() {
        when(tasks.selectList(any(Wrapper.class))).thenReturn(List.of());
        service.dispatchDue();
        assertThat(states).containsExactly("UNKNOWN");
        verify(port, never()).sendFielded(any(), any(), any(), any());
    }

    @Test
    void definiteTemporaryRejectionRetriesOnlyWithinBound() {
        task(0);
        doThrow(new WxSubscribeException("busy", true)).when(port).sendFielded(any(), any(), any(), any());
        service.dispatchDue();
        assertThat(states).contains("RETRY");
        states.clear();
        task(2);
        service.dispatchDue();
        assertThat(states).contains("FAILED").doesNotContain("RETRY");
    }

    @Test
    void stubOrMissingMappingCannotAdvertiseCapability() {
        when(port.configuredForApp(any())).thenReturn(false);
        assertThat(service.capability()).isEqualTo(new StoredValueNotificationService.Capability(
                "WXSUB", false, null, "TEMPLATE_UNCONFIGURED"));
    }

    @Test
    void authenticatedPageUsesOnlyEncodedStoreAndDocumentArguments() {
        assertThat(StoredValueNotificationServiceImpl.page("S &1", "D/2"))
                .isEqualTo("/pages/stored-value-records/index?storeNo=S+%261&focusDocumentNo=D%2F2");
    }
}
