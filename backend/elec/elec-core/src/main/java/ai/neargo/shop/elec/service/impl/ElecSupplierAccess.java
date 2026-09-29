package ai.neargo.shop.elec.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.elec.config.ConditionalOnElec;
import ai.neargo.shop.elec.entity.ElcSupplier;
import ai.neargo.shop.elec.entity.ElcSupplierMember;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMapper;
import ai.neargo.shop.elec.mapper.ElecMappers.SupplierMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

/**
 * 「这个登录的人代表哪家供应商」。本域不走平台的 DataScope，<b>每个供应商面的查询都要显式带
 * supplier_no</b>，而 supplier_no 只能从这里来 —— 不接受端上传进来的供应商号。
 */
@ConditionalOnElec
@Component
public class ElecSupplierAccess {

    private final SupplierMapper supplierMapper;
    private final SupplierMemberMapper memberMapper;

    public ElecSupplierAccess(SupplierMapper supplierMapper, SupplierMemberMapper memberMapper) {
        this.supplierMapper = supplierMapper;
        this.memberMapper = memberMapper;
    }

    /** @return 没入驻为 null */
    public ElcSupplier of(String userNo) {
        ElcSupplierMember m = memberMapper.selectOne(Wrappers.<ElcSupplierMember>lambdaQuery()
                .eq(ElcSupplierMember::getAccountRef, userNo)
                .eq(ElcSupplierMember::getStatus, ElcSupplierMember.STATUS_ACTIVE));
        if (m == null) {
            return null;
        }
        return supplierMapper.selectOne(Wrappers.<ElcSupplier>lambdaQuery()
                .eq(ElcSupplier::getSupplierNo, m.getSupplierNo()));
    }

    /** 上传、续期、看库存都要求「是供应商且没被暂停」 */
    public ElcSupplier requireActive(String userNo) {
        ElcSupplier s = of(userNo);
        if (s == null) {
            throw BizException.of(ErrorCode.ELEC_NOT_SUPPLIER);
        }
        if (!ElcSupplier.STATUS_ACTIVE.equals(s.getStatus())) {
            throw BizException.of(ErrorCode.ELEC_SUPPLIER_SUSPENDED);
        }
        return s;
    }
}
