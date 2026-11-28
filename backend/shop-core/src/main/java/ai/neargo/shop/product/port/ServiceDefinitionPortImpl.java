package ai.neargo.shop.product.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.product.entity.PrdFulfillmentRequirement;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdServiceDefinition;
import ai.neargo.shop.product.mapper.ProductMappers.FulfillmentRequirementMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.ServiceDefinitionMapper;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 服务定义的商品侧实现（TDD-美业域迁移新库 §2.8 P3）。
 *
 * <p>发布在商品事务里进行：先锁商品行（与商家保存商品同一把锁），核对期望版本，把上一版的其余行与要求原样带到新版本、
 * 写入这一行与它的要求；发布的是默认行时，再把时长定向镜像到商品行（只改 {@code duration_min}，不动乐观锁版本）。
 */
@Component
public class ServiceDefinitionPortImpl implements ServiceDefinitionPort {
    private static final int SCHEMA_VERSION = 1;

    private final ServiceGoods services;
    private final ServiceDefinitionMapper definitions;
    private final FulfillmentRequirementMapper requirements;
    private final GoodsMapper goods;
    private final ObjectMapper json;

    public ServiceDefinitionPortImpl(ServiceGoods services, ServiceDefinitionMapper definitions,
            FulfillmentRequirementMapper requirements, GoodsMapper goods, ObjectMapper json) {
        this.services = services;
        this.definitions = definitions;
        this.requirements = requirements;
        this.goods = goods;
        this.json = json;
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public Definition publish(Publish command) {
        validate(command);
        return DataScopeContext.executeWithoutScope(() -> {
            var product = services.resolve(command.entityNo(), command.storeNo(), command.skuNo(), true).goods();
            String goodsNo = product.getGoodsNo();
            String rowSku = command.skuOverride() ? command.skuNo() : DEFAULT_SKU;
            if (command.sourceRef() != null) {
                var replay = definitions.selectOne(Wrappers.<PrdServiceDefinition>lambdaQuery()
                        .eq(PrdServiceDefinition::getTenantNo, "MAIN").eq(PrdServiceDefinition::getGoodsNo, goodsNo)
                        .eq(PrdServiceDefinition::getSourceRef, command.sourceRef())
                        .eq(PrdServiceDefinition::getSkuNo, rowSku));
                if (replay != null) { return view(product, replay.getDefinitionVersion(), rowSku); }
            }
            int current = definitions.currentVersion(goodsNo);
            if (command.expectedVersion() != current) { throw BizException.of(ErrorCode.CONFLICT); }
            List<PrdServiceDefinition> previous = current == 0 ? List.of() : definitions.rows(goodsNo, current);
            if (command.skuOverride() && previous.stream().noneMatch(row -> row.getSkuNo().isEmpty())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            int next = current + 1;
            var now = LocalDateTime.now(ZoneOffset.UTC);
            for (var row : previous) {
                if (!row.getSkuNo().equals(rowSku)) { definitions.insert(carry(row, next, command, now)); }
            }
            definitions.insert(row(command, product, rowSku, next, now));
            if (current > 0) {
                for (var old : requirements.ofDefinition(goodsNo, current)) {
                    if (!skuOf(old).equals(rowSku)) { requirements.insert(carry(old, next)); }
                }
            }
            if (command.requirements() != null) {
                for (var input : command.requirements()) {
                    requirements.insert(requirement(input, product, rowSku, next));
                }
            }
            if (!command.skuOverride()) {
                // 时长的镜像：只改这一列，不动 version（发布不让商家手上的草稿提示「有人中途改过」）
                goods.update(null, Wrappers.<PrdGoods>lambdaUpdate()
                        .set(PrdGoods::getDurationMin, command.timing().durationMin())
                        .eq(PrdGoods::getTenantNo, "MAIN").eq(PrdGoods::getEntityNo, command.entityNo())
                        .eq(PrdGoods::getGoodsNo, goodsNo));
            }
            return view(product, next, rowSku);
        });
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER, readOnly = true)
    public Definition current(String entityNo, String storeNo, String skuNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            var product = services.resolve(entityNo, storeNo, skuNo, false).goods();
            int version = definitions.currentVersion(product.getGoodsNo());
            return version == 0 ? null : view(product, version, skuNo);
        });
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER, readOnly = true)
    public Definition byVersion(String entityNo, String storeNo, String skuNo, int definitionVersion) {
        if (definitionVersion < 1) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return DataScopeContext.executeWithoutScope(() ->
                view(services.resolve(entityNo, storeNo, skuNo, false).goods(), definitionVersion, skuNo));
    }

    @Override
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER, readOnly = true)
    public List<Definition> list(String entityNo, String storeNo, Instant at) {
        if (entityNo == null || entityNo.isBlank() || storeNo == null || storeNo.isBlank() || at == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return DataScopeContext.executeWithoutScope(() -> {
            List<Definition> result = new ArrayList<>();
            for (var head : definitions.versionsAt(entityNo, storeNo, LocalDateTime.ofInstant(at, ZoneOffset.UTC))) {
                String goodsNo = head.getGoodsNo();
                int version = head.getDefinitionVersion();
                var rows = definitions.rows(goodsNo, version);
                var reqs = requirements.ofDefinition(goodsNo, version);
                for (var row : rows) { result.add(view(rows, reqs, row.getSkuNo())); }
            }
            return result;
        });
    }

    // ---------------------------------------------------------------- 读：合并覆盖行与默认行

    private Definition view(PrdGoods product, int version, String skuNo) {
        var rows = definitions.rows(product.getGoodsNo(), version);
        return rows.isEmpty() ? null : view(rows, requirements.ofDefinition(product.getGoodsNo(), version), skuNo);
    }

    /** 这个 SKU（或默认行）在一组行里的定义：时长与缓冲取适用行，步骤、注意事项、要求在覆盖行为空时沿用默认行。 */
    private Definition view(List<PrdServiceDefinition> rows, List<PrdFulfillmentRequirement> reqs, String skuNo) {
        var row = ServiceDefinitionMapper.applicable(rows, skuNo);
        var base = ServiceDefinitionMapper.applicable(rows, DEFAULT_SKU);
        if (row == null || base == null) { return null; }
        boolean override = !row.getSkuNo().isEmpty();
        var own = reqs.stream().filter(r -> skuOf(r).equals(row.getSkuNo())).toList();
        var applied = override && own.isEmpty()
                ? reqs.stream().filter(r -> skuOf(r).isEmpty()).toList() : own;
        return new Definition(row.getEntityNo(), row.getStoreNo(), row.getGoodsNo(), skuNo,
                row.getDefinitionVersion(), override,
                new Timing(row.getDurationMin(), row.getBufferBeforeMin(), row.getBufferAfterMin()),
                List.of(json.readValue(row.getSteps() != null ? row.getSteps() : orEmpty(base.getSteps()),
                        Step[].class)),
                List.of(json.readValue(row.getNotes() != null ? row.getNotes() : orEmpty(base.getNotes()),
                        String[].class)),
                applied.stream().map(r -> new Requirement(r.getRoleCode(), r.getResourceType(), r.getSkillGoodsNo(),
                        r.getQty(), r.getSequenceNo(), r.getOffsetMin(), r.getDurationMin(), r.getSelectionRule()))
                        .toList(),
                row.getStatus(), row.getSnapshotHash(), row.getPublishedAt().toInstant(ZoneOffset.UTC));
    }

    private static String orEmpty(String jsonArray) { return jsonArray == null ? "[]" : jsonArray; }

    private static String skuOf(PrdFulfillmentRequirement requirement) {
        return requirement.getSkuNo() == null ? DEFAULT_SKU : requirement.getSkuNo();
    }

    // ---------------------------------------------------------------- 写

    private static void validate(Publish command) {
        var timing = command == null ? null : command.timing();
        if (timing == null || timing.durationMin() < 1 || timing.bufferBeforeMin() < 0
                || timing.bufferAfterMin() < 0 || command.expectedVersion() < 0
                || command.sourceRef() != null && (command.sourceRef().isBlank() || command.sourceRef().length() > 64)
                || command.actor() != null && command.actor().length() > 64
                || !command.skuOverride() && (command.steps() == null || command.notes() == null)
                || command.requirements() != null && command.requirements().stream().anyMatch(r -> r == null
                        || r.qty() < 1 || r.roleCode() == null && r.resourceType() == null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private PrdServiceDefinition row(Publish command, PrdGoods product, String skuNo, int version,
            LocalDateTime now) {
        var row = new PrdServiceDefinition();
        row.setEntityNo(command.entityNo());
        row.setStoreNo(product.getStoreNo());
        row.setGoodsNo(product.getGoodsNo());
        row.setSkuNo(skuNo);
        row.setDefinitionVersion(version);
        row.setDurationMin(command.timing().durationMin());
        row.setBufferBeforeMin(command.timing().bufferBeforeMin());
        row.setBufferAfterMin(command.timing().bufferAfterMin());
        row.setSteps(command.steps() == null ? null : json.writeValueAsString(command.steps()));
        row.setNotes(command.notes() == null ? null : json.writeValueAsString(command.notes()));
        row.setSchemaVersion(SCHEMA_VERSION);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("timing", command.timing());
        content.put("steps", command.steps());
        content.put("notes", command.notes());
        content.put("requirements", command.requirements());
        row.setSnapshotHash(sha256(json.writeValueAsString(content)));
        row.setStatus(ACTIVE);
        stamp(row, command, now);
        return row;
    }

    /** 上一版的其余行原样带到新版本，只换版本号与这次发布的操作号、发布人、时间。 */
    private static PrdServiceDefinition carry(PrdServiceDefinition old, int version, Publish command,
            LocalDateTime now) {
        var row = new PrdServiceDefinition();
        row.setEntityNo(old.getEntityNo());
        row.setStoreNo(old.getStoreNo());
        row.setGoodsNo(old.getGoodsNo());
        row.setSkuNo(old.getSkuNo());
        row.setDefinitionVersion(version);
        row.setDurationMin(old.getDurationMin());
        row.setBufferBeforeMin(old.getBufferBeforeMin());
        row.setBufferAfterMin(old.getBufferAfterMin());
        row.setSteps(old.getSteps());
        row.setNotes(old.getNotes());
        row.setSchemaVersion(old.getSchemaVersion());
        row.setSnapshotHash(old.getSnapshotHash());
        row.setStatus(old.getStatus());
        stamp(row, command, now);
        return row;
    }

    private static void stamp(PrdServiceDefinition row, Publish command, LocalDateTime now) {
        row.setSourceRef(command.sourceRef());
        row.setPublishedBy(command.actor());
        row.setPublishedAt(now);
    }

    private static PrdFulfillmentRequirement requirement(Requirement input, PrdGoods product, String skuNo,
            int version) {
        var row = new PrdFulfillmentRequirement();
        row.setEntityNo(product.getEntityNo());
        row.setRequirementNo(BizKey.next(BizKey.FULFILLMENT_REQUIREMENT));
        row.setGoodsNo(product.getGoodsNo());
        row.setSkuNo(skuNo.isEmpty() ? null : skuNo);
        row.setStoreNo(product.getStoreNo());
        row.setDefinitionVersion(version);
        row.setRequirementType(input.roleCode() != null ? PrdFulfillmentRequirement.TYPE_ROLE
                : PrdFulfillmentRequirement.TYPE_RESOURCE);
        row.setRoleCode(input.roleCode());
        row.setResourceType(input.resourceType());
        row.setSkillGoodsNo(input.skillGoodsNo());
        row.setQty(input.qty());
        row.setSequenceNo(input.sequenceNo());
        row.setOffsetMin(input.offsetMin());
        row.setDurationMin(input.durationMin());
        row.setSelectionRule(input.selectionRule());
        return row;
    }

    private static PrdFulfillmentRequirement carry(PrdFulfillmentRequirement old, int version) {
        var row = new PrdFulfillmentRequirement();
        row.setEntityNo(old.getEntityNo());
        row.setRequirementNo(BizKey.next(BizKey.FULFILLMENT_REQUIREMENT));
        row.setGoodsNo(old.getGoodsNo());
        row.setSkuNo(old.getSkuNo());
        row.setStoreNo(old.getStoreNo());
        row.setDefinitionVersion(version);
        row.setRequirementType(old.getRequirementType());
        row.setRoleCode(old.getRoleCode());
        row.setResourceType(old.getResourceType());
        row.setStationNo(old.getStationNo());
        row.setTaskRole(old.getTaskRole());
        row.setSkillGoodsNo(old.getSkillGoodsNo());
        row.setQty(old.getQty());
        row.setSequenceNo(old.getSequenceNo());
        row.setOffsetMin(old.getOffsetMin());
        row.setDurationMin(old.getDurationMin());
        row.setSelectionRule(old.getSelectionRule());
        return row;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
