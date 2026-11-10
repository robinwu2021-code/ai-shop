package ai.neargo.shop.portal.config;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeInternalApi;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.ExecutionRuntimeBindings;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.function.Function;

/**
 * Host Native requests current source facts from Beauty; it never fabricates source authority
 * locally.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shop.beauty", name = "deployment", havingValue = "standalone")
public class TaskRuntimeAuthorityConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(ServiceTaskStartAuthorityPort.class)
    RemoteTaskRuntimeAuthorityPort remoteTaskRuntimeAuthorityPort(
            InternalHttp http, @Value("${shop.services.internal-token:}") String token) {
        return new RemoteTaskRuntimeAuthorityPort(
                http.client(
                        ServiceName.BEAUTY,
                        ExecutionRuntimeInternalApi.class,
                        Duration.ofSeconds(5)),
                token);
    }

    public static final class RemoteTaskRuntimeAuthorityPort
            implements ServiceTaskStartAuthorityPort, ServiceTaskWorkflowAuthorityPort {
        private final ExecutionRuntimeInternalApi api;
        private final InternalBindings human;
        private final ExecutionRuntimeBindings originals;

        public RemoteTaskRuntimeAuthorityPort(ExecutionRuntimeInternalApi api, String token) {
            this.api = api;
            this.human = new InternalBindings(token);
            this.originals = new ExecutionRuntimeBindings(token);
        }

        private <P, R> R human(
                ExecutionReference ref,
                P request,
                String action,
                Function<InternalCall<P>, InternalReply<R>> send) {
            DelegatedIdentityContext.outside();
            var identity = DelegatedIdentityContext.current();
            if (!"MERCHANT".equals(identity.realm())) throw BizException.of(ErrorCode.FORBIDDEN);
            var call =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    ExecutionRuntimeInternalApi.ENDPOINT,
                                    action,
                                    identity,
                                    ref.scope().entityNo(),
                                    ref.scope().storeNo(),
                                    ref.executionNo()),
                            request);
            String digest = human.digest(call);
            try {
                var reply = ServiceCalls.call(ServiceName.BEAUTY, () -> send.apply(call));
                human.require(call, reply);
                return reply.result();
            } catch (ServiceCallException failed) {
                throw ExecutionRemoteErrors.map(failed, digest);
            }
        }

        @Override
        public StartAuthority authorizeCurrent(StartAuthorityLookup request) {
            var result =
                    human(request.reference(), request, "START_AUTHORITY", api::startAuthority);
            result.validate();
            if (!request.equals(result.original())) throw invalid();
            return result;
        }

        @Override
        public WorkflowAuthority authorizeCurrent(WorkflowAuthorityLookup request) {
            var result =
                    human(
                            request.reference(),
                            request,
                            "WORKFLOW_AUTHORITY",
                            api::workflowAuthority);
            result.validate();
            if (!request.equals(result.lookup())) throw invalid();
            return result;
        }

        @Override
        public StartAuthorityOriginal queryOriginal(StartAuthorityLookup request) {
            DelegatedIdentityContext.outside();
            var call =
                    new MachineCall<>(
                            Purpose.START_AUTHORITY_ORIGINAL, request.reference(), request);
            String digest = originals.digest(call);
            try {
                var reply =
                        ServiceCalls.call(
                                ServiceName.BEAUTY, () -> api.startAuthorityOriginal(call));
                originals.require(call, reply);
                var result = reply.result();
                result.validate();
                if (!request.equals(result.original())) throw invalid();
                return result;
            } catch (ServiceCallException failed) {
                throw ExecutionRemoteErrors.map(failed, digest);
            }
        }

        private static BizException invalid() {
            return BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }
}
