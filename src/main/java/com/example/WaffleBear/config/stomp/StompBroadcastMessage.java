package com.example.WaffleBear.config.stomp;

public record StompBroadcastMessage(
        String sourceNode,
        String destination,
        String payloadJson
) {
}
