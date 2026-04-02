package com.example.WaffleBear.config.sse;

public record SseBroadcastMessage(
        String sourceNode,
        Long userId,
        String eventName,
        String payloadJson
) {
}
