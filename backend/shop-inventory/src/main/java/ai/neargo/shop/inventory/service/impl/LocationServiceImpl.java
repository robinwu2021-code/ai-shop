package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.inventory.entity.InvLocation;
import ai.neargo.shop.inventory.mapper.InventoryMappers.LocationMapper;
import ai.neargo.shop.inventory.service.LocationService;
import ai.neargo.shop.inventory.support.InvEnums;
import ai.neargo.shop.inventory.support.InvKeys;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 库位实现。 */
@ConditionalOnInventory
@Service
public class LocationServiceImpl implements LocationService {

    private final LocationMapper locationMapper;

    public LocationServiceImpl(LocationMapper locationMapper) {
        this.locationMapper = locationMapper;
    }

    @Override
    public List<InvLocation> list(String ownerId) {
        return locationMapper.selectList(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId)
                .orderByAsc(InvLocation::getKind).orderByAsc(InvLocation::getId));
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String createWarehouse(String ownerId, String name, String operator) {
        return create(ownerId, name, InvEnums.LocationKind.WAREHOUSE, false, operator);
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public void setSource(String ownerId, String locationId, String sourceLocationId, String operator) {
        InvLocation self = require(ownerId, locationId);
        if (sourceLocationId == null || sourceLocationId.isBlank()) {
            /*
             * **清空必须显式 set null，不能靠 updateById**。
             *
             * MyBatis-Plus 的 updateById 默认跳过 null 字段（FieldStrategy.NOT_NULL）——
             * `self.setSourceLocationId(null)` 之后再 updateById，那一列**原样不动**。
             * 于是商家在库位页选「发自己的」，界面没报错、也没生效，
             * 而下一单照旧从源仓扣 —— 他会以为是自己没点到。
             */
            locationMapper.update(null, Wrappers.<InvLocation>lambdaUpdate()
                    .eq(InvLocation::getOwnerId, ownerId)
                    .eq(InvLocation::getLocationId, locationId)
                    .set(InvLocation::getSourceLocationId, null)
                    .set(InvLocation::getUpdatedBy, operator));
            return;
        }

        if (sourceLocationId.equals(locationId)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        InvLocation source = require(ownerId, sourceLocationId);
        /*
         * **不允许链式**：被指向的那个自己必须不再指向别处。
         *
         * 拦在保存这一步，而不是在扣减时顺着找 —— 顺着找的第一个后果是环（死循环），
         * 第二个是「货到底从哪出」要看链条有多长，而没人会去看。
         */
        if (source.getSourceLocationId() != null && !source.getSourceLocationId().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        self.setSourceLocationId(sourceLocationId);
        self.setUpdatedBy(operator);
        locationMapper.updateById(self);
    }

    @Override
    public String resolveStockLocation(String ownerId, String locationId) {
        InvLocation self = require(ownerId, locationId);
        String source = self.getSourceLocationId();
        return source == null || source.isBlank() ? locationId : source;
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String transitLocation(String ownerId) {
        return findOrCreate(ownerId, InvEnums.LocationKind.TRANSIT, "在途");
    }

    @Override
    @Transactional(transactionManager = "invTransactionManager")
    public String defaultLocation(String ownerId) {
        InvLocation row = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getIsDefault, 1)
                .last("LIMIT 1"));
        if (row != null) {
            return row.getLocationId();
        }
        return create(ownerId, "默认库位", InvEnums.LocationKind.WAREHOUSE, true, "SYSTEM");
    }

    // ────────────────────────────────────────────────────────────────────

    private String findOrCreate(String ownerId, String kind, String name) {
        InvLocation row = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getKind, kind)
                .last("LIMIT 1"));
        return row != null ? row.getLocationId() : create(ownerId, name, kind, false, "SYSTEM");
    }

    private String create(String ownerId, String name, String kind, boolean isDefault, String operator) {
        InvLocation row = new InvLocation();
        row.setLocationId(InvKeys.next(InvKeys.LOCATION));
        row.setOwnerId(ownerId);
        row.setName(name);
        row.setKind(kind);
        row.setIsDefault(isDefault ? 1 : 0);
        row.setStatus(InvEnums.MasterStatus.ACTIVE);
        row.setCreatedBy(operator);
        locationMapper.insert(row);
        return row.getLocationId();
    }

    private InvLocation require(String ownerId, String locationId) {
        InvLocation row = locationMapper.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId, ownerId).eq(InvLocation::getLocationId, locationId));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }
}
