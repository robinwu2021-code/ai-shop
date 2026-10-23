package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintAgentDtos.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintAgentServiceImpl implements PrintAgentService {
    private final PrnAgentMapper agents;
    private final PrnPrinterMapper printers;
    private final PrintAgentSecrets secrets;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;

    public PrintAgentServiceImpl(
            PrnAgentMapper a,
            PrnPrinterMapper p,
            PrintAgentSecrets s,
            PrintOperationService o,
            PrintJsonCodec j) {
        agents = a;
        printers = p;
        secrets = s;
        ops = o;
        json = j;
    }

    private PrnAgent agent(String no, PrintActor a) {
        var g = agents.selectOne(PrintSupport.<PrnAgent>scoped(scope(a)).eq("agent_no", no));
        require(g != null, "print.notFound");
        return g;
    }

    private AgentVO vo(PrnAgent a) {
        return new AgentVO(
                a.getAgentNo(),
                a.getStoreNo(),
                a.getName(),
                a.getPlatform(),
                a.getStatus(),
                a.getSessionEpoch(),
                a.getCredentialVersion(),
                instant(a.getLastSeenAt()),
                a.getRevision());
    }

    public PageData<AgentVO> listAgents(ListAgentsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                agents.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnAgent>scoped(scope(a))
                                .eq(q.status() != null, "status", q.status())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream().map(this::vo).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PrintOperation createAgent(CreateAgentReq r, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_AGENT",
                json.freeze(r).sha256(),
                () -> {
                    require(
                            a.storeNo().equals(r.storeNo())
                                    && "WINDOWS".equals(r.platform())
                                    && r.name() != null
                                    && !r.name().isBlank()
                                    && r.name().length() <= 64,
                            "print.invalidAgent");
                    var g = init(new PrnAgent(), scope(a));
                    g.setAgentNo(no("AG"));
                    g.setStoreNo(a.storeNo());
                    g.setName(r.name());
                    g.setPlatform(r.platform());
                    g.setStatus("PAIRING");
                    g.setCredentialVersion(0);
                    g.setSessionEpoch(0L);
                    g.setCapabilities("{}");
                    agents.insert(g);
                    return succeeded(
                            "CREATE_AGENT",
                            "AGENT",
                            g.getAgentNo(),
                            json.freeze(Map.of("agentNo", g.getAgentNo())));
                });
    }

    public AgentDetailVO getAgent(String no, PrintActor a) {
        var g = agent(no, a);
        var discovered =
                new java.util.ArrayList<
                        ai.neargo.shop.print.api.dto.PrintProtocolDtos.DiscoveredPrinter>();
        var registered =
                json.tree(g.getCapabilities() == null ? "{}" : g.getCapabilities())
                        .path("registeredDevices");
        for (var property : registered.properties())
            discovered.add(
                    new ai.neargo.shop.print.api.dto.PrintProtocolDtos.DiscoveredPrinter(
                            property.getKey(), property.getValue().path("queueName").asText()));
        return new AgentDetailVO(
                vo(g),
                printers
                        .selectList(PrintSupport.<PrnPrinter>scoped(scope(a)).eq("agent_no", no))
                        .stream()
                        .map(PrnPrinter::getPrinterNo)
                        .toList(),
                discovered);
    }

    private void save(PrnAgent a) {
        a.setRevision(a.getRevision() + 1);
        a.setUpdatedAt(now());
        agents.updateById(a);
    }

    public PrintOperation issueEnrollment(String no, IssueEnrollmentReq r, PrintActor a) {
        return ops.execute(
                a,
                "ISSUE_AGENT_ENROLLMENT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var g = agent(no, a);
                    revision(g, r.expectedRevision());
                    require(!"REVOKED".equals(g.getStatus()), "print.agentRevoked");
                    String code = secrets.random();
                    String sealed = secrets.seal(code, r.clientPublicKey());
                    g.setEnrollmentHash(PrintHashes.sha256(code));
                    g.setEnrollmentExpiresAt(now().plusMinutes(10));
                    g.setStatus("PAIRING");
                    g.setCredentialHash(null);
                    g.setSessionEpoch(g.getSessionEpoch() + 1);
                    save(g);
                    return succeeded(
                            "ISSUE_AGENT_ENROLLMENT",
                            "AGENT",
                            no,
                            json.freeze(
                                    Map.of(
                                            "agentNo",
                                            no,
                                            "sealedEnrollment",
                                            sealed,
                                            "expiresAt",
                                            instant(g.getEnrollmentExpiresAt()).toString())));
                });
    }

    public PrintOperation revokeAgent(String no, RevokeAgentReq r, PrintActor a) {
        return ops.execute(
                a,
                "REVOKE_AGENT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var g = agent(no, a);
                    revision(g, r.expectedRevision());
                    g.setStatus("REVOKED");
                    g.setCredentialHash(null);
                    g.setEnrollmentHash(null);
                    g.setRevokedAt(now());
                    g.setSessionEpoch(g.getSessionEpoch() + 1);
                    save(g);
                    return succeeded(
                            "REVOKE_AGENT", "AGENT", no, json.freeze(Map.of("agentNo", no)));
                });
    }

    public PrintOperation rotateAgentCredential(
            String no, RotateAgentCredentialReq r, PrintActor a) {
        return ops.execute(
                a,
                "ROTATE_AGENT_CREDENTIAL",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var g = agent(no, a);
                    revision(g, r.expectedRevision());
                    require("ACTIVE".equals(g.getStatus()), "print.agentUnavailable");
                    int version = g.getCredentialVersion() + 1;
                    String token = no + "." + version + "." + secrets.random();
                    String sealed = secrets.seal(token, r.clientPublicKey());
                    g.setCredentialVersion(version);
                    g.setCredentialHash(PrintHashes.sha256(token));
                    g.setSessionEpoch(g.getSessionEpoch() + 1);
                    save(g);
                    return succeeded(
                            "ROTATE_AGENT_CREDENTIAL",
                            "AGENT",
                            no,
                            json.freeze(
                                    Map.of(
                                            "agentNo",
                                            no,
                                            "sealedCredential",
                                            sealed,
                                            "credentialVersion",
                                            version)));
                });
    }
}
