package com.example.WaffleBear.legup.openhexagon;

import com.example.WaffleBear.config.stomp.ClusteredStompPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

@Component
@RequiredArgsConstructor
public class OpenHexagonDisconnectListener {

    private final OpenHexagonService openHexagonService;
    private final ClusteredStompPublisher stompPublisher;

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        OpenHexagonDto.LobbySnapshot snapshot = openHexagonService.leave(event.getSessionId());
        stompPublisher.send("/sub/game/openhexagon/lobby", snapshot);
    }
}
