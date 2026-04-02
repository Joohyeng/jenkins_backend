package com.example.WaffleBear.config.sse;

import com.example.WaffleBear.config.realtime.RealtimeNodeIdProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SseService {
    public static final String CHANNEL = "wafflebear:realtime:sse";

    private final SseEmitterStore emitterStore;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RealtimeNodeIdProvider nodeIdProvider;

    public void sendTitleUpdate(Long postId, String newTitle, List<Long> userIds) {
        Map<String, Object> data = new HashMap<>();
        data.put("postId", postId);
        data.put("title", newTitle);

        for (Long userId : userIds) {
            sendEventToUser(userId, "title-updated", data);  // ✅ 통일
        }
    }

    public void sendRoleChanged(Long targetUserIdx, Long postIdx, String newRole) {
        Map<String, Object> payload = Map.of(
                "postIdx", postIdx,
                "newRole", newRole
        );
        sendEventToUser(targetUserIdx, "role-changed", payload);
    }
    // ✅ 공통 전송 헬퍼
    public void sendEventToUser(Long userId, String eventName, Object data) {
        if (userId == null || !StringUtils.hasText(eventName)) {
            return;
        }

        String payloadJson = serializePayload(data);
        deliverEventToUser(userId, eventName, payloadJson);
        publish(userId, eventName, payloadJson);
    }

    void deliverEventToUser(Long userId, String eventName, String payloadJson) {
        SseEmitter emitter = emitterStore.get(userId);
        if (emitter == null) return;

        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payloadJson));
        } catch (IOException e) {
            emitterStore.remove(userId);
            emitter.completeWithError(e);
        }
    }

    public boolean isConnected(Long userId) {
        return emitterStore.getEmitters().containsKey(userId);
    }

    private void publish(Long userId, String eventName, String payloadJson) {
        try {
            SseBroadcastMessage message = new SseBroadcastMessage(
                    nodeIdProvider.getNodeId(),
                    userId,
                    eventName,
                    payloadJson
            );
            redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE broadcast message", e);
        }
    }

    private String serializePayload(Object data) {
        if (data == null) {
            return "null";
        }

        if (data instanceof CharSequence sequence) {
            return sequence.toString();
        }

        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize SSE payload", e);
        }
    }
}
