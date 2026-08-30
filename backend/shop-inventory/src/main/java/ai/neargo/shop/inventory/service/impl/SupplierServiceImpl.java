package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.entity.InvSupplier;
import ai.neargo.shop.inventory.mapper.InventoryMappers.SupplierMapper;
import ai.neargo.shop.inventory.service.SupplierService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 供应商档案实现。 */
@ConditionalOnInventory
@Service
public class SupplierServiceImpl implements SupplierService {

    private final SupplierMapper supplierMapper;

    public SupplierServiceImpl(SupplierMapper supplierMapper) {
        this.supplierMapper = supplierMapper;
    }

    @Override
    public List<InvSupplier> list(String ownerId, String keyword, boolean activeOnly) {
        return supplierMapper.selectList(Wrappers.<InvSupplier>lambdaQuery()
                .eq(InvSupplier::getOwnerId, ownerId)
                .eq(activeOnly, InvSupplier::getStatus, InvEnums.MasterStatus.ACTIVE)
                .like(keyword != null && !keyword.isBlank(), InvSupplier::getName, keyword)
                // 在用的排前面，其次按建档顺序 —— 停用的沉底但不消失
                .orderByAsc(InvSupplier::getStatus).orderByAsc(InvSupplier::getId));
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String create(String ownerId, InvSupplier form, String operator) {
        String name = trimmed(form.getName());
        if (name == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        /*
         * **重名在这里就拦掉，不靠唯一键的异常兜底。**
         * 靠兜底的话商家看到的是「系统开小差了」，而他其实只需要知道
         * 「这家已经建过了」—— 两句话让人做的事完全不同。
         *
         * 唯一键仍然要有（uk_sup_name）：它防的是并发下两个请求同时通过这一段。
         */
        if (findByName(ownerId, name) != null) throw BizException.of(ErrorCode.CONFLICT);

        InvSupplier row = new InvSupplier();
        row.setSupplierNo(InvKeys.next(InvKeys.SUPPLIER));
        row.setOwnerId(ownerId);
        row.setName(name);
        row.setShortName(trimmed(form.getShortName()));
        row.setContactName(trimmed(form.getContactName()));
        row.setContactPhone(trimmed(form.getContactPhone()));
        row.setRemark(trimmed(form.getRemark()));
        row.setPlatformSupplierNo(trimmed(form.getPlatformSupplierNo()));
        row.setStatus(InvEnums.MasterStatus.ACTIVE);
        row.setCreatedBy(operator);
        supplierMapper.insert(row);
        return row.getSupplierNo();
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void update(String ownerId, String supplierNo, InvSupplier form, String operator) {
        InvSupplier row = require(ownerId, supplierNo);

        /*
         * **引用平台档案的只能改备注。** 名称与联系方式跟平台走 ——
         * 让商家在这儿改，下次平台同步就被盖掉，而他不会知道自己改的东西没了。
         * 备注是例外：那是他自己的话。
         */
        boolean fromPlatform = row.getPlatformSupplierNo() != null
                && !row.getPlatformSupplierNo().isBlank();
        if (!fromPlatform) {
            String name = trimmed(form.getName());
            if (name == null) throw BizException.of(ErrorCode.BAD_REQUEST);
            if (!name.equals(row.getName())) {
                InvSupplier dup = findByName(ownerId, name);
                // 改成另一家已存在的名字 —— 与建档同一条规矩
                if (dup != null && !dup.getSupplierNo().equals(supplierNo)) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                row.setName(name);
            }
            row.setShortName(trimmed(form.getShortName()));
            row.setContactName(trimmed(form.getContactName()));
            row.setContactPhone(trimmed(form.getContactPhone()));
        }
        row.setRemark(trimmed(form.getRemark()));
        row.setUpdatedBy(operator);
        supplierMapper.updateById(row);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void setActive(String ownerId, String supplierNo, boolean active, String operator) {
        InvSupplier row = require(ownerId, supplierNo);
        row.setStatus(active ? InvEnums.MasterStatus.ACTIVE : InvEnums.MasterStatus.ARCHIVED);
        row.setUpdatedBy(operator);
        supplierMapper.updateById(row);
    }

    private InvSupplier findByName(String ownerId, String name) {
        return supplierMapper.selectOne(Wrappers.<InvSupplier>lambdaQuery()
                .eq(InvSupplier::getOwnerId, ownerId)
                .eq(InvSupplier::getName, name));
    }

    private InvSupplier require(String ownerId, String supplierNo) {
        InvSupplier row = supplierMapper.selectOne(Wrappers.<InvSupplier>lambdaQuery()
                .eq(InvSupplier::getOwnerId, ownerId)
                .eq(InvSupplier::getSupplierNo, supplierNo));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    /** 空白当成没填。**前后空格要去掉** —— 否则「老周粮油 」与「老周粮油」是两家 */
    private static String trimmed(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
