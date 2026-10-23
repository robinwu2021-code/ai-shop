package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public interface AgentCredentialStore {
    Optional<OpaqueAgentCredential> load();

    void saveSealedCredential(String sealedCredential, String clientKeyAlias);

    void revokeLocalCredential();
}
