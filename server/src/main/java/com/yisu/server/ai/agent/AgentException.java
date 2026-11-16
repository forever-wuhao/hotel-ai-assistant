package com.yisu.server.ai.agent;

public class AgentException extends RuntimeException {
    public final int status;
    public final String code;
    public AgentException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
