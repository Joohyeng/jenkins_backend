package com.example.WaffleBear.legup.openhexagon;

import com.example.WaffleBear.legup.LegupGameAccessService;
import com.example.WaffleBear.config.stomp.ClusteredStompPublisher;
import com.example.WaffleBear.user.model.AuthUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class OpenHexagonSocketController {

    private final LegupGameAccessService legupGameAccessService;
    private final OpenHexagonService openHexagonService;
    private final ClusteredStompPublisher stompPublisher;

    @MessageMapping("/game/openhexagon/join")
    public void join(
            @Payload OpenHexagonDto.JoinRequest req,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(openHexagonService.join(headerAccessor.getSessionId(), user, req));
    }

    @MessageMapping("/game/openhexagon/ready")
    public void ready(
            @Payload OpenHexagonDto.ReadyRequest req,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(openHexagonService.setReady(headerAccessor.getSessionId(), user, req));
    }

    @MessageMapping("/game/openhexagon/score")
    public void score(
            @Payload OpenHexagonDto.ScoreRequest req,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(openHexagonService.submitScore(headerAccessor.getSessionId(), user, req));
    }

    @MessageMapping("/game/openhexagon/state")
    public void state(
            @Payload OpenHexagonDto.StateRequest req,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(openHexagonService.updateState(headerAccessor.getSessionId(), user, req));
    }

    @MessageMapping("/game/openhexagon/leave")
    public void leave(
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        requireUser(principal);
        broadcast(openHexagonService.leave(headerAccessor.getSessionId()));
    }

    private void broadcast(OpenHexagonDto.LobbySnapshot snapshot) {
        stompPublisher.send("/sub/game/openhexagon/lobby", snapshot);
    }

    private AuthUserDetails requireUser(Principal principal) {
        Authentication auth = (Authentication) principal;
        return (AuthUserDetails) auth.getPrincipal();
    }
}
