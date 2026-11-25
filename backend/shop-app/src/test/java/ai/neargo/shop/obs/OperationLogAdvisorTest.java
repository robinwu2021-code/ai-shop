package ai.neargo.shop.obs;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.config.obs.OperationLogAdvisor;
import ai.neargo.shop.observability.OperationLog;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationLogAdvisorTest {

    @Test
    void recordsCommitRollbackAndRejectionWithoutInspectingArguments() {
        var logger = (Logger) LoggerFactory.getLogger("operation");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var proxy = new ProxyFactory(new Operations());
        proxy.addAdvisor(new OperationLogAdvisor());
        var operations = (Operations) proxy.getProxy();

        try {
            complete(operations, appender, TransactionSynchronization.STATUS_COMMITTED, "OK");
            appender.list.clear();
            complete(operations, appender, TransactionSynchronization.STATUS_ROLLED_BACK, "ROLLED_BACK");

            appender.list.clear();
            assertThatThrownBy(() -> operations.apply("credential-fixture", true)).isInstanceOf(BizException.class);
            assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("outcome=REJECTED", "bizCode=10409").doesNotContain("credential-fixture"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void complete(Operations operations, ListAppender<ILoggingEvent> appender,
                                 int status, String outcome) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(operations.apply("credential-fixture", false)).isEqualTo("credential-fixture");
            assertThat(appender.list).isEmpty();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(status));
            assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                    .contains("event=fixture.apply", "outcome=" + outcome).doesNotContain("credential-fixture"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    public static class Operations {
        @OperationLog("fixture.apply")
        public String apply(String value, boolean reject) {
            if (reject) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return value;
        }
    }
}
