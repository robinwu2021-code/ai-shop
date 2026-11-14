package ai.neargo.shop.printing.center.service.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintAgentAuthenticationServiceImpl implements PrintAgentAuthenticationService {
    private final PrnAgentMapper agents;
    private final PrintAgentSecrets secrets;

    public PrintAgentAuthenticationServiceImpl(PrnAgentMapper a, PrintAgentSecrets s) {
        agents = a;
        secrets = s;
    }

    public PrintAgentIdentity authenticate(
            OpaqueAgentCredential credential, AgentRequestPurpose purpose) {
        String token = credential.value();
        require(token != null && token.length() < 256, "print.agentUnauthorized");
        String[] parts = token.split("[.]");
        require(parts.length == 3, "print.agentUnauthorized");
        var a =
                agents.selectOne(
                        new QueryWrapper<PrnAgent>().eq("agent_no", parts[0]).last("LIMIT 1"));
        require(
                a != null
                        && "ACTIVE".equals(a.getStatus())
                        && parts[1].equals(String.valueOf(a.getCredentialVersion()))
                        && secrets.matches(token, a.getCredentialHash()),
                "print.agentUnauthorized");
        return new PrintAgentIdentity(
                a.getTenantNo(),
                a.getEntityNo(),
                a.getStoreNo(),
                a.getAgentNo(),
                a.getCredentialVersion(),
                a.getSessionEpoch());
    }
}
