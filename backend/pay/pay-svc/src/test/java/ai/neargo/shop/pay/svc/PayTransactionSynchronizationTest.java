package ai.neargo.shop.pay.svc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class PayTransactionSynchronizationTest {
    @Test void standaloneNonTransactionalBoundaryDoesNotCreateResourceSynchronization() {
        DataSource dataSource = mock(DataSource.class);
        var manager = new EntitlementPayBootstrap().payTransactions(dataSource);
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        template.executeWithoutResult(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        });
        verifyNoInteractions(dataSource);
    }
}
