package com.example.WaffleBear.legup.rps;

import com.example.WaffleBear.config.stomp.ClusteredStompPublisher;
import com.example.WaffleBear.legup.LegupGameAccessService;
import com.example.WaffleBear.user.model.AuthUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class RockPaperScissorsSocketController {

    private final LegupGameAccessService legupGameAccessService;
    private final RockPaperScissorsService rockPaperScissorsService;
    private final ClusteredStompPublisher stompPublisher;

    @MessageMapping("/game/rps/join")
    public void join(
            @Payload RockPaperScissorsDto.JoinRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(rockPaperScissorsService.join(headerAccessor.getSessionId(), user, request));
    }

    @MessageMapping("/game/rps/chat")
    public void chat(
            @Payload RockPaperScissorsDto.ChatRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(rockPaperScissorsService.sendChat(headerAccessor.getSessionId(), user, request));
    }

    @MessageMapping("/game/rps/typing")
    public void typing(
            @Payload RockPaperScissorsDto.TypingRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(rockPaperScissorsService.updateTyping(headerAccessor.getSessionId(), user, request));
    }

    @MessageMapping("/game/rps/choice")
    public void choice(
            @Payload RockPaperScissorsDto.ChoiceRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(rockPaperScissorsService.submitChoice(headerAccessor.getSessionId(), user, request));
    }

    @MessageMapping("/game/rps/reset")
    public void reset(
            @Payload RockPaperScissorsDto.ResetRequest request,
            Principal principal,
            SimpMessageHeaderAccessor headerAccessor
    ) {
        AuthUserDetails user = requireUser(principal);
        legupGameAccessService.ensurePlayable(user);
        broadcast(rockPaperScissorsService.resetRound(headerAccessor.getSessionId(), user, request));
    }

    @MessageMapping("/game/rps/leave")
    public void leave(Principal principal, SimpMessageHeaderAccessor headerAccessor) {
        requireUser(principal);
        broadcast(rockPaperScissorsService.leave(headerAccessor.getSessionId()));
    }

    public void broadcast(RockPaperScissorsDto.RoomState state) {
        if (state == null || !StringUtils.hasText(state.roomId())) {
            return;
        }

        stompPublisher.send("/sub/game/rps/room/" + state.roomId(), state);
    }

    public void broadcast(RockPaperScissorsDto.LeaveOutcome outcome) {
        if (outcome == null || outcome.state() == null || !StringUtils.hasText(outcome.roomId())) {
            return;
        }

        stompPublisher.send("/sub/game/rps/room/" + outcome.roomId(), outcome.state());
    }

    private AuthUserDetails requireUser(Principal principal) {
        Authentication authentication = (Authentication) principal;
        return (AuthUserDetails) authentication.getPrincipal();
    }
}
