package com.example.WaffleBear.config.stomp;

import com.example.WaffleBear.config.realtime.RealtimeNodeIdProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ClusteredStompPublisher {
    public static final String CHANNEL = "wafflebear:realtime:stomp";

    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeNodeIdProvider nodeIdProvider;

    public void send(String destination, Object payload) {
        if (!StringUtils.hasText(destination)) {
            return;
        }

        String payloadJson = toPayloadJson(payload);
        messagingTemplate.convertAndSend(destination, payloadJson);
        publish(destination, payloadJson);
    }

    private void publish(String destination, String payloadJson) {
        try {
            StompBroadcastMessage message = new StompBroadcastMessage(
                    nodeIdProvider.getNodeId(),
                    destination,
                    payloadJson
            );
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize STOMP broadcast message", e);
        }
    }

    private String toPayloadJson(Object payload) {
        if (payload == null) {
            return "null";
        }

        if (payload instanceof CharSequence sequence) {
            return sequence.toString();
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize STOMP payload", e);
        }
    }
}
