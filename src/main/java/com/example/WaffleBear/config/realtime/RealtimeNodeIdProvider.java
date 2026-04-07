package com.example.WaffleBear.config.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Component
public class RealtimeNodeIdProvider {
    private final String nodeId;

    public RealtimeNodeIdProvider(@Value("${HOSTNAME:}") String hostname) {
        this.nodeId = StringUtils.hasText(hostname) ? hostname : UUID.randomUUID().toString();
    }

    public String getNodeId() {
        return nodeId;
    }
}
