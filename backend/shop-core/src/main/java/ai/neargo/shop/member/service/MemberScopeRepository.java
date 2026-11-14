package ai.neargo.shop.member.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.jdbc.JdbcTransactionStore;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Member mappings share the existing owner-neutral JDBC transaction primitives. */
@Repository
public class MemberScopeRepository extends JdbcTransactionStore {
    public MemberScopeRepository(JdbcTemplate jdbc,
                                 @Qualifier("transactionManager") PlatformTransactionManager manager,
                                 ObjectMapper json) {
        super(jdbc, manager, json);
    }

    public MemberScopeModels.ScopeMember member(Map<String, Object> row) {
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return new MemberScopeModels.ScopeMember(s(row, "scope_member_no"), s(row, "customer_no"),
                s(row, "scope_type"), s(row, "scope_no"), s(row, "first_store_no"), s(row, "status"));
    }
}
