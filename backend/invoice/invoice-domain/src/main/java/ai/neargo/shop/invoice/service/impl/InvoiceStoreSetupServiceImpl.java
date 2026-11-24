package ai.neargo.shop.invoice.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreItemVO;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsReq;
import ai.neargo.shop.invoice.dto.InvoiceDtos.StoreSettingsVO;
import ai.neargo.shop.invoice.entity.IvcStoreItem;
import ai.neargo.shop.invoice.entity.IvcStoreSetting;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.StoreItemMapper;
import ai.neargo.shop.invoice.mapper.InvoiceMappers.StoreSettingMapper;
import ai.neargo.shop.invoice.service.InvoiceStoreSetupService;
import ai.neargo.shop.spi.invoice.InvoicePort.Scope;
import ai.neargo.shop.spi.invoice.InvoicePort.StoreSettings;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link InvoiceStoreSetupService} 的实现。设定一店一行（唯一键兜并发首存），品项上限在门店设定行锁下数，
 * 两个店长同时加第 50 项不会都成功。
 */
@Service
public class InvoiceStoreSetupServiceImpl implements InvoiceStoreSetupService {

    private final StoreSettingMapper settings;
    private final StoreItemMapper items;

    public InvoiceStoreSetupServiceImpl(StoreSettingMapper settings, StoreItemMapper items) {
        this.settings = settings;
        this.items = items;
    }

    @Override
    public StoreSettingsVO settings(Scope scope) {
        var row = settings.selectOne(settingOf(scope));
        return row == null ? new StoreSettingsVO(null, StoreSettings.MANUAL)
                : new StoreSettingsVO(row.getDefaultTaxType(), row.getInvoiceTiming());
    }

    @Override
    @Transactional
    public StoreSettingsVO saveSettings(Scope scope, StoreSettingsReq request) {
        var row = lockSetting(scope);
        // 「清空默认税别」要显式 set null：updateById 默认跳过 null 字段
        settings.update(null, new UpdateWrapper<IvcStoreSetting>().eq("id", row.getId())
                .set("default_tax_type", request.defaultTaxType()).set("invoice_timing", request.invoiceTiming())
                .set("updated_by", scope.actorNo()).set("updated_at", now()));
        return new StoreSettingsVO(request.defaultTaxType(), request.invoiceTiming());
    }

    @Override
    public List<StoreItemVO> items(Scope scope) {
        return items.selectList(itemsOf(scope).orderByAsc("sort", "id")).stream().map(InvoiceStoreSetupServiceImpl::view)
                .toList();
    }

    @Override
    @Transactional
    public StoreItemVO addItem(Scope scope, StoreItemReq request) {
        lockSetting(scope);
        if (items.selectCount(itemsOf(scope)) >= MAX_STORE_ITEMS)
            throw BizException.of(ErrorCode.INVOICE_STORE_ITEM_LIMIT, MAX_STORE_ITEMS);
        var row = new IvcStoreItem();
        row.setStoreItemNo(BizKey.next(BizKey.INVOICE_STORE_ITEM));
        row.setTenantNo(scope.tenantNo());
        row.setEntityNo(scope.entityNo());
        row.setStoreNo(scope.storeNo());
        apply(row, request, scope);
        row.setCreatedAt(row.getUpdatedAt());
        items.insert(row);
        return view(row);
    }

    @Override
    @Transactional
    public StoreItemVO updateItem(Scope scope, String storeItemNo, StoreItemReq request) {
        var row = items.selectOne(itemsOf(scope).eq("store_item_no", storeItemNo).last("FOR UPDATE"));
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        apply(row, request, scope);
        items.updateById(row);
        return view(row);
    }

    @Override
    public void deleteItem(Scope scope, String storeItemNo) {
        items.delete(itemsOf(scope).eq("store_item_no", storeItemNo));
    }

    @Override
    public StoreItemVO item(Scope scope, String storeItemNo) {
        var row = items.selectOne(itemsOf(scope).eq("store_item_no", storeItemNo));
        return row == null ? null : view(row);
    }

    /** 锁住本店设定行，没有就先建一行默认（无默认税别、手动开）；并发首存撞唯一键的一方读回赢家那行。 */
    private IvcStoreSetting lockSetting(Scope scope) {
        var row = settings.selectOne(settingOf(scope).last("FOR UPDATE"));
        if (row != null) return row;
        var fresh = new IvcStoreSetting();
        fresh.setTenantNo(scope.tenantNo());
        fresh.setEntityNo(scope.entityNo());
        fresh.setStoreNo(scope.storeNo());
        fresh.setInvoiceTiming(StoreSettings.MANUAL);
        fresh.setUpdatedBy(scope.actorNo());
        fresh.setCreatedAt(now());
        fresh.setUpdatedAt(fresh.getCreatedAt());
        try {
            settings.insert(fresh);
            return fresh;
        } catch (DuplicateKeyException race) {
            return settings.selectOne(settingOf(scope).last("FOR UPDATE"));
        }
    }

    private static void apply(IvcStoreItem row, StoreItemReq request, Scope scope) {
        row.setName(request.name().strip());
        row.setTaxType(request.taxType());
        row.setSort(request.sort() == null ? 0 : request.sort());
        row.setEnabled(request.enabled() == null || request.enabled());
        row.setUpdatedBy(scope.actorNo());
        row.setUpdatedAt(now());
    }

    private static QueryWrapper<IvcStoreSetting> settingOf(Scope scope) {
        return new QueryWrapper<IvcStoreSetting>().eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo())
                .eq("store_no", scope.storeNo());
    }

    private static QueryWrapper<IvcStoreItem> itemsOf(Scope scope) {
        return new QueryWrapper<IvcStoreItem>().eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo())
                .eq("store_no", scope.storeNo());
    }

    private static StoreItemVO view(IvcStoreItem row) {
        return new StoreItemVO(row.getStoreItemNo(), row.getName(), row.getTaxType(),
                row.getSort() == null ? 0 : row.getSort(), Boolean.TRUE.equals(row.getEnabled()));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
