package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.entity.ElcStockBatch;
import ai.neargo.shop.elec.mapper.ElecMappers.StockBatchMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 解析失败的批次也要留一条记录（原件已经落在未入库区了）。
 *
 * <p><b>独立事务</b>：上传随后会把原来的错误抛给端上，那个异常会回滚上传的事务 ——
 * 记录写在同一个事务里的话，跟着一起没了，等于没记。单独成一个 bean 是因为同类内部调用不过代理，
 * {@code REQUIRES_NEW} 写在同一个类的方法上不生效。
 */
@ConditionalOnElec
@Component
public class BatchFailureRecorder {

    private final StockBatchMapper batchMapper;

    public BatchFailureRecorder(StockBatchMapper batchMapper) {
        this.batchMapper = batchMapper;
    }

    @Transactional(transactionManager = "elecTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void record(ElcStockBatch b, String failCode) {
        b.setStatus(ElcStockBatch.STATUS_FAILED);
        b.setFailCode(failCode);
        batchMapper.insert(b);
    }
}
