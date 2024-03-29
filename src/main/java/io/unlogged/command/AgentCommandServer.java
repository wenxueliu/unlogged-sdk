package io.unlogged.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AgentCommandServer extends NanoHTTPD {
    private final ServerMetadata serverMetadata;
    ObjectMapper objectMapper = new ObjectMapper();
    private AgentCommandExecutor agentCommandExecutor;
    private String pingResponseBody;

    public AgentCommandServer(int port, ServerMetadata serverMetadata) {
        super(port);
        this.serverMetadata = serverMetadata;
        init();
    }

    public AgentCommandServer(String hostname, int port, ServerMetadata serverMetadata) {
        super(hostname, port);
        this.serverMetadata = serverMetadata;
        init();
    }

    public void init() {
        AgentCommandResponse pingResponse = new AgentCommandResponse();
        pingResponse.setMessage("ok");
        pingResponse.setResponseType(ResponseType.NORMAL);
        try {
            pingResponse.setMethodReturnValue(serverMetadata);
            pingResponseBody = objectMapper.writeValueAsString(pingResponse);
        } catch (JsonProcessingException e) {
            // should never happen
        }
    }

    public void setAgentCommandExecutor(AgentCommandExecutor agentCommandExecutor) {
        this.agentCommandExecutor = agentCommandExecutor;
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> bodyParams = new HashMap<>();
        try {
            session.parseBody(bodyParams);
        } catch (IOException | ResponseException e) {
            return newFixedLengthResponse("{\"message\": \"" + e.getMessage() + "\", }");
        }
        String requestBodyText = bodyParams.get("postData");
        String postBody = session.getQueryParameterString();

        String requestPath = session.getUri();
        Method requestMethod = session.getMethod();
        System.err.println("[" + requestMethod + "] " + requestPath + ": " + postBody + " - " + requestBodyText);
        if (requestPath.equals("/ping")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", pingResponseBody);
        }
        try {
            AgentCommandRequest agentCommandRequest = objectMapper.readValue(
                    postBody != null ? postBody : requestBodyText,
                    AgentCommandRequest.class);
            AgentCommandResponse commandResponse;
            commandResponse = processRequest(agentCommandRequest);
            String responseBody = objectMapper.writeValueAsString(commandResponse);
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseBody);
        } catch (Throwable e) {
            e.printStackTrace();
            AgentCommandErrorResponse agentCommandErrorResponse = new AgentCommandErrorResponse(e.getMessage());
            if (e instanceof NoSuchMethodException) {
                agentCommandErrorResponse.setResponseType(ResponseType.FAILED);
            } else {
                agentCommandErrorResponse.setResponseType(ResponseType.EXCEPTION);
            }
            String errorResponseBody = null;
            try {
                errorResponseBody = objectMapper.writeValueAsString(agentCommandErrorResponse);
            } catch (JsonProcessingException ex) {
                return newFixedLengthResponse("{\"message\": \"" + ex.getMessage() + "\"}");
            }

            return newFixedLengthResponse(errorResponseBody);
        }
    }

    private AgentCommandResponse processRequest(AgentCommandRequest agentCommandRequest) throws Exception {
        AgentCommandResponse commandResponse;
        switch (agentCommandRequest.getCommand()) {
            case EXECUTE:
                commandResponse = this.agentCommandExecutor.executeCommand(agentCommandRequest);
                break;
            case INJECT_MOCKS:
                commandResponse = this.agentCommandExecutor.injectMocks(agentCommandRequest);
                break;
            case REGISTER_CLASS:
                commandResponse = this.agentCommandExecutor.registerClass(agentCommandRequest);
                break;
            case REMOVE_MOCKS:
                commandResponse = this.agentCommandExecutor.removeMocks(agentCommandRequest);
                break;
            case INJECT_TRACE:
                commandResponse = this.agentCommandExecutor.injectTrace(agentCommandRequest);
                break;
            case REMOVE_TRACE:
                commandResponse = this.agentCommandExecutor.removeTrace(agentCommandRequest);
                break;
            default:
                commandResponse = this.agentCommandExecutor.notSupport(agentCommandRequest);

                break;
        }
        return commandResponse;
    }
}