package com.example.WaffleBear.config.sse;

import com.example.WaffleBear.config.realtime.RealtimeNodeIdProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseRedisSubscriber implements MessageListener {
    private final ObjectMapper objectMapper;
    private final RealtimeNodeIdProvider nodeIdProvider;
    private final SseService sseService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            return;
        }

        try {
            SseBroadcastMessage envelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8),
                    SseBroadcastMessage.class
            );

            if (envelope == null || !StringUtils.hasText(envelope.eventName())) {
                return;
            }

            if (nodeIdProvider.getNodeId().equals(envelope.sourceNode())) {
                return;
            }

            sseService.deliverEventToUser(envelope.userId(), envelope.eventName(), envelope.payloadJson());
        } catch (Exception e) {
            log.warn("Failed to process Redis SSE message", e);
        }
    }
}
