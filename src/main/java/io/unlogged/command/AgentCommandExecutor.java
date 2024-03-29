package io.unlogged.command;

import io.unlogged.AgentCommandRawResponse;

public interface AgentCommandExecutor {
    AgentCommandRawResponse executeCommandRaw(AgentCommandRequest agentCommandRequest);

    AgentCommandResponse executeCommand(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse injectMocks(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse removeMocks(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse injectTrace(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse removeTrace(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse registerClass(AgentCommandRequest agentCommandRequest) throws Exception;

    AgentCommandResponse notSupport(AgentCommandRequest agentCommandRequest) throws Exception;
}