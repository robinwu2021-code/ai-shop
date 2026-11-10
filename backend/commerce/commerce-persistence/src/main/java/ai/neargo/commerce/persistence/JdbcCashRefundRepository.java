package ai.neargo.commerce.persistence;

import ai.neargo.commerce.core.offline.CashRefundRepository;
import ai.neargo.commerce.api.mall.MallException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.util.*;

public final class JdbcCashRefundRepository implements CashRefundRepository {
    private final DataSource ds;private final JdbcTemplate jdbc;private final JsonMapper json=JsonMapper.builder().build();
    public JdbcCashRefundRepository(DataSource ds){this.ds=ds;jdbc=new JdbcTemplate(ds);}
    public Optional<Refund> find(String tenant,String no,boolean lock){JdbcWriteGuard.require(ds,tenant);return jdbc.query("SELECT snapshot_json FROM mall_offline_cash_refund WHERE tenant_no=? AND refund_no=?"+(lock?" FOR UPDATE":""),(r,n)->json.readValue(r.getString(1),Refund.class),tenant,no).stream().findFirst();}
    public List<Refund> list(String tenant,String order){JdbcWriteGuard.require(ds,tenant);return jdbc.query("SELECT snapshot_json FROM mall_offline_cash_refund WHERE tenant_no=? AND order_no=? ORDER BY refund_no",(r,n)->json.readValue(r.getString(1),Refund.class),tenant,order);}
    public List<String> pending(String tenant,String after,int limit){JdbcWriteGuard.require(ds,tenant);return jdbc.queryForList("SELECT refund_no FROM mall_offline_cash_refund WHERE tenant_no=? AND refund_no>? AND status IN ('PREPARED','PAYOUT_ACCEPTED') ORDER BY refund_no LIMIT ?",String.class,tenant,after==null?"":after,limit);}
    public void save(Refund r){String tenant=r.target().store().tenantNo();JdbcWriteGuard.require(ds,tenant);
        if(find(tenant,r.command().refundNo(),true).isPresent())jdbc.update("UPDATE mall_offline_cash_refund SET status=?,snapshot_json=? WHERE tenant_no=? AND refund_no=?",r.status(),json.writeValueAsString(r),tenant,r.command().refundNo());
        else try{jdbc.update("INSERT INTO mall_offline_cash_refund(tenant_no,refund_no,order_no,store_no,payment_no,status,snapshot_json) VALUES(?,?,?,?,?,?,?)",tenant,r.command().refundNo(),r.target().orderNo(),r.target().store().storeNo(),r.command().paymentNo(),r.status(),json.writeValueAsString(r));}catch(DuplicateKeyException conflict){throw new MallException("REFUND_ALREADY_PENDING");}
    }
}
