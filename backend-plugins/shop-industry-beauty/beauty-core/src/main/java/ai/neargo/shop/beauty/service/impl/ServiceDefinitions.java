package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.PublishServiceDefinitionVersionReq;
import ai.neargo.shop.beauty.dto.WorkOrderRequirementVO;
import ai.neargo.shop.beauty.dto.WorkOrderStepTemplateVO;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * 美业读写服务定义的唯一入口（TDD-美业域迁移新库 §2.8 P5）：定义归商品域，经 {@link ServiceDefinitionPort} 读写。
 *
 * <p>美业字段的取值只在这里换算（用户 2026-11-28 定「字段不动、换值」）：
 * {@code definitionNo} = 商品号，{@code definitionVersionNo} = 商品内的定义版本号（十进制字符串），
 * {@code revision} = 同一个版本号。定义摘要 {@link Snapshot#hash()} 也只在这里算，冻结与核对用同一个公式。
 */
public class ServiceDefinitions {
    /** P1 的固定步骤：美业发布的定义只有这一步。 */
    public static final List<WorkOrderStepTemplateVO> STEPS = List.of(new WorkOrderStepTemplateVO("SERVICE", 0));

    private final ObjectProvider<ServiceDefinitionPort> port;
    private final ObjectMapper json;

    public ServiceDefinitions(ObjectProvider<ServiceDefinitionPort> port, ObjectMapper json) {
        this.port = port;
        this.json = json;
    }

    /**
     * 一个 SKU 在某版本下的定义，已换成美业的字段。
     *
     * @param definitionNo 商品号
     * @param definitionVersionNo 定义版本号（十进制字符串）
     * @param revision 同一个版本号
     * @param inputs 发布时的要求（按顺序、角色、资源排好），用于摘要与回显
     * @param requirements 带要求编号的要求，用于预约分派与任务冻结
     */
    public record Snapshot(String definitionNo, String storeNo, String skuNo, String definitionVersionNo,
            long revision, String status, PublishServiceDefinitionVersionReq.Duration duration,
            List<PublishServiceDefinitionVersionReq.Requirement> inputs, List<WorkOrderRequirementVO> requirements,
            List<WorkOrderStepTemplateVO> steps, List<String> notes, String hash, Instant publishedAt) {
        public boolean active() { return ServiceDefinitionPort.ACTIVE.equals(status); }
    }

    /** 按 SKU 发布（写默认行还是覆盖行由商品域按 SKU 数定）；{@code sourceRef} 相同的重放返回原版本。 */
    public Snapshot publish(String entityNo, String storeNo, String actorNo, PublishServiceDefinitionVersionReq req,
            String sourceRef) {
        var timing = new ServiceDefinitionPort.Timing(req.duration().minutes(), req.duration().bufferBeforeMin(),
                req.duration().bufferAfterMin());
        var steps = STEPS.stream().map(s -> new ServiceDefinitionPort.Step(s.stepCode(), s.sequenceNo())).toList();
        var requirements = req.requirements().stream().map(r -> new ServiceDefinitionPort.Requirement(null,
                r.roleCode(), r.resourceType(), r.skillGoodsNo(), r.qty(), r.sequenceNo(), r.offsetMin(),
                r.durationMin(), r.selectionRule())).toList();
        return view(port.getObject().publish(new ServiceDefinitionPort.Publish(entityNo, storeNo, req.skuNo(), null,
                Math.toIntExact(req.expectedRevision()), timing, steps, req.notes(), requirements, sourceRef,
                actorNo)), req.skuNo());
    }

    /** 这个 SKU 现在的定义；没有时为 {@code null}。 */
    public Snapshot current(String entityNo, String storeNo, String skuNo) {
        var definition = port.getObject().current(entityNo, storeNo, skuNo);
        return definition == null ? null : view(definition, skuNo);
    }

    /** 这个 SKU 在 {@code definitionVersionNo} 那一版的定义；版本号不合法或没有这一版时为 {@code null}。 */
    public Snapshot byVersion(String entityNo, String storeNo, String skuNo, String definitionVersionNo) {
        int version = version(definitionVersionNo);
        if (version < 1) { return null; }
        var definition = port.getObject().byVersion(entityNo, storeNo, skuNo, version);
        return definition == null ? null : view(definition, skuNo);
    }

    /** 门店下各服务在 {@code at} 时刻的定义：每个商品的默认行（{@code skuNo} 为空串）与各 SKU 覆盖行。 */
    public List<Snapshot> list(String entityNo, String storeNo, Instant at) {
        return port.getObject().list(entityNo, storeNo, at).stream().map(d -> view(d, d.skuNo())).toList();
    }

    /** 美业的版本号字段取值。 */
    public static String versionNo(int definitionVersion) { return Integer.toString(definitionVersion); }

    /** 解析美业的版本号字段；不是正整数时为 0（旧的 BTDV… 编号也落在这里）。 */
    public static int version(String definitionVersionNo) {
        if (definitionVersionNo == null || !definitionVersionNo.matches("[1-9][0-9]{0,8}")) { return 0; }
        return Integer.parseInt(definitionVersionNo);
    }

    private Snapshot view(ServiceDefinitionPort.Definition d, String skuNo) {
        var duration = new PublishServiceDefinitionVersionReq.Duration(d.timing().durationMin(),
                d.timing().bufferBeforeMin(), d.timing().bufferAfterMin());
        var inputs = d.requirements().stream().map(r -> new PublishServiceDefinitionVersionReq.Requirement(
                r.roleCode(), r.resourceType(), r.skillGoodsNo(), r.qty(), r.sequenceNo(), r.offsetMin(),
                r.durationMin(), r.selectionRule())).sorted(ORDER).toList();
        var requirements = d.requirements().stream().map(r -> new WorkOrderRequirementVO(r.requirementNo(),
                r.roleCode(), r.resourceType(), r.skillGoodsNo(), r.qty(), r.sequenceNo(), r.offsetMin(),
                r.durationMin(), r.selectionRule())).toList();
        var steps = d.steps().stream().map(s -> new WorkOrderStepTemplateVO(s.stepCode(), s.sequenceNo())).toList();
        return new Snapshot(d.goodsNo(), d.storeNo(), skuNo, versionNo(d.definitionVersion()), d.definitionVersion(),
                d.status(), duration, inputs, requirements, steps, d.notes(), hash(skuNo, duration, inputs, d.notes()),
                d.publishedAt());
    }

    private static final Comparator<PublishServiceDefinitionVersionReq.Requirement> ORDER = Comparator
            .comparing(PublishServiceDefinitionVersionReq.Requirement::sequenceNo)
            .thenComparing(PublishServiceDefinitionVersionReq.Requirement::roleCode)
            .thenComparing(PublishServiceDefinitionVersionReq.Requirement::resourceType);

    /** 定义摘要：SKU、时长、要求（排好序），有注意事项时再并进去。冻结任务与核对任务都用它。 */
    private String hash(String skuNo, PublishServiceDefinitionVersionReq.Duration duration,
            List<PublishServiceDefinitionVersionReq.Requirement> inputs, List<String> notes) {
        String hash = IdempotencyScope.requestHash(skuNo, json.writeValueAsString(duration),
                json.writeValueAsString(inputs));
        return notes.isEmpty() ? hash
                : IdempotencyScope.requestHash("BEAUTY_DEFINITION_NOTES_V1", hash, json.writeValueAsString(notes));
    }
}
