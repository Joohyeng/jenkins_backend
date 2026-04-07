package com.example.WaffleBear.chat;

import com.example.WaffleBear.chat.model.dto.ChatParticipantsDto;
import com.example.WaffleBear.chat.model.dto.ChatRoomsDto;
import com.example.WaffleBear.chat.model.entity.ChatMessages;
import com.example.WaffleBear.chat.model.entity.ChatParticipants;
import com.example.WaffleBear.chat.model.entity.ChatRooms;
import com.example.WaffleBear.chat.model.entity.MessageType;
import com.example.WaffleBear.config.stomp.ClusteredStompPublisher;
import com.example.WaffleBear.user.model.User;
import com.example.WaffleBear.user.repository.UserRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service

public class ChatRoomService {
    private static final LocalDateTime DEFAULT_JOINED_AT = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final ParticipantsRepository participantsRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ClusteredStompPublisher stompPublisher;
    private final ChatPresenceService chatPresenceService;
    private final ChatListCacheService chatListCacheService;

    public ChatRoomService(ChatRoomRepository chatRoomRepository,
                           UserRepository userRepository,
                           ParticipantsRepository participantsRepository,
                           ChatMessageRepository chatMessageRepository,
                           @Lazy ClusteredStompPublisher stompPublisher,
                            ChatPresenceService chatPresenceService,
                           ChatListCacheService chatListCacheService) {
        this.chatRoomRepository = chatRoomRepository;
        this.userRepository = userRepository;
        this.participantsRepository = participantsRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.stompPublisher = stompPublisher;
        this.chatPresenceService = chatPresenceService;
        this.chatListCacheService = chatListCacheService;
    }


    private void sendSystemMessage(Long roomIdx, String text, MessageType type) {
        Map<String, Object> payload = Map.of(
                "roomIdx",     roomIdx,
                "contents",    text,
                "messageType", type.name(),
                "createdAt",   LocalDateTime.now().toString()
        );
        stompPublisher.send("/sub/chat/room/" + roomIdx, payload);
    }

        // 1. 방 생성 (내부에 초대 로직 포함)
        @Transactional
        public Long createChatRoom(ChatRoomsDto.ChatRoomsReq dto, Long myIdx) {
            // 방 엔티티 생성 및 저장
            ChatRooms room = chatRoomRepository.save(dto.toEntity());

            // 이메일 리스트를 User 객체 리스트로 변환 (유효성 검사 포함)
            Set<User> invitees = new HashSet<>();

            // 내 정보 추가
            invitees.add(userRepository.findById(myIdx)
                    .orElseThrow(() -> new RuntimeException("내 정보를 찾을 수 없습니다.")));

            // 초대받은 이메일들 처리
            if (dto.getParticipantsEmail() != null) {
                for (String email : dto.getParticipantsEmail()) {
                    User user = userRepository.findByEmail(email)
                            .orElseThrow(() -> new RuntimeException("존재하지 않는 유저입니다: " + email));
                    invitees.add(user);
                }
            }
            this.addParticipantsToRoom(room, invitees);
            evictChatListCachesByRoom(room.getIdx());
            return room.getIdx();
        }

    @Transactional
    public void inviteUsersByEmail(Long roomId, List<String> emails) {
        ChatRooms room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("방이 존재하지 않습니다."));

        // 1. DB에서 이메일에 해당하는 유저들을 한꺼번에 가져옴 (쿼리 1번)
        List<User> foundUsers = userRepository.findAllByEmailIn(emails);

        // 2. 입력받은 이메일 개수와 DB에서 찾은 유저 수가 다르면 없는 유저가 섞여 있는 것
        if (foundUsers.size() != emails.size()) {
            // 어떤 이메일이 없는지 찾아서 알려주면 더 친절함
            Set<String> foundEmails = foundUsers.stream()
                    .map(User::getEmail)
                    .collect(Collectors.toSet());

            String missingEmails = emails.stream()
                    .filter(e -> !foundEmails.contains(e))
                    .collect(Collectors.joining(", "));

            throw new RuntimeException("존재하지 않는 유저가 포함되어 있습니다: " + missingEmails);
        }

        // 3. 공통 메서드로 전달 (이미 Set<User> 형태이므로 중복 자동 제거)
        this.addParticipantsToRoom(room, new HashSet<>(foundUsers));
        for (User user : foundUsers) {
            sendSystemMessage(roomId, user.getName() + "님이 입장했습니다.", MessageType.ENTER);
        }
        evictChatListCachesByRoom(roomId);
    }

    // [개선된 공통 로직] 중복 방지 강화
    @Transactional
    public int inviteUsers(Long roomId, Long actorUserIdx, Collection<Long> userIds) {
        ChatRooms room = chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new IllegalArgumentException("채팅방이 존재하지 않습니다."));

        if (!participantsRepository.existsByChatRoomsIdxAndUsersIdx(roomId, actorUserIdx)) {
            throw new IllegalArgumentException("채팅방 초대 권한이 없습니다.");
        }

        List<User> targetUsers = userIds == null
                ? List.of()
                : userIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(userId -> userRepository.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다. idx: " + userId)))
                .filter(user -> !Objects.equals(user.getIdx(), actorUserIdx))
                .toList();

        Set<User> newUsers = targetUsers.stream()
                .filter(user -> participantsRepository.findByChatRoomsIdxAndUsersIdx(roomId, user.getIdx()).isEmpty())
                .collect(Collectors.toSet());

        if (newUsers.isEmpty()) {
            return 0;
        }

        this.addParticipantsToRoom(room, newUsers);
        newUsers.forEach(user ->
                sendSystemMessage(roomId, user.getName() + "님이 입장했습니다.", MessageType.ENTER)
        );
        evictChatListCachesByRoom(roomId);
        return newUsers.size();
    }

    private void addParticipantsToRoom(ChatRooms room, Set<User> users) {
        for (User user : users) {
            participantsRepository.findByChatRoomsIdxAndUsersIdx(room.getIdx(), user.getIdx())
                    .ifPresentOrElse(
                            existing -> {
                                existing.updateJoinedAt(); // ✅ 재입장 시 joinedAt 갱신
                                participantsRepository.save(existing);
                            },
                            () -> {
                                participantsRepository.save(ChatParticipantsDto.Create.toEntity(room, user));
                            }
                    );
        }
    }


    public ChatRoomsDto.PageRes list(int page, int size, Long userIdx) {
        ChatRoomsDto.PageRes cached = chatListCacheService.get(userIdx, page, size);
        if (cached != null) {
            return cached;
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<ChatParticipants> result = participantsRepository.findAllByUsersIdx(userIdx, pageable);
        List<ChatParticipants> pageItems = result.getContent();

        List<Long> roomIds = pageItems.stream()
                .map(p -> p.getChatRooms().getIdx())
                .distinct()
                .toList();

        if (roomIds.isEmpty()) {
            ChatRoomsDto.PageRes emptyResponse = ChatRoomsDto.PageRes.from(
                    result,
                    Map.of(),
                    Map.of(),
                    Map.of()
            );
            chatListCacheService.put(userIdx, page, size, emptyResponse);
            return emptyResponse;
        }

        Map<Long, Long> participantCountMap = participantsRepository.countParticipantsByRoomIds(roomIds).stream()
                .collect(Collectors.toMap(
                        ParticipantsRepository.RoomParticipantCountView::getRoomIdx,
                        ParticipantsRepository.RoomParticipantCountView::getParticipantCount
                ));

        Map<Long, Long> unreadMap = chatMessageRepository.countUnreadCountsByUserAndRoomIds(
                        userIdx,
                        roomIds,
                        DEFAULT_JOINED_AT
                ).stream()
                .collect(Collectors.toMap(
                        ChatMessageRepository.RoomUnreadCountView::getRoomIdx,
                        ChatMessageRepository.RoomUnreadCountView::getUnreadCount
                ));

        Map<Long, String> lastMessageMap = pageItems.stream()
                .collect(Collectors.toMap(
                        p -> p.getChatRooms().getIdx(),
                        p -> {
                            ChatRooms room = p.getChatRooms();
                            LocalDateTime joinedAt = p.getJoinedAt() != null
                                    ? p.getJoinedAt()
                                    : DEFAULT_JOINED_AT;

                            if (room.getLastMessageTime() == null || room.getLastMessageTime().isBefore(joinedAt)) {
                                return "";
                            }

                            return room.getLastMessage() == null ? "" : room.getLastMessage();
                        }
                ));

        ChatRoomsDto.PageRes response = ChatRoomsDto.PageRes.from(
                result,
                unreadMap,
                lastMessageMap,
                participantCountMap
        );

        chatListCacheService.put(userIdx, page, size, response);
        return response;
    }


    private String getPreviewMessage(ChatMessages message) {
        if (message == null) return "메시지가 없습니다.";
        if (message.isDeleted()) return "삭제된 메시지입니다.";
        if (message.getMessageType() == MessageType.IMAGE) return "사진";
        if (message.getMessageType() == MessageType.FILE) return "문서";

        String contents = message.getContents();
        return (contents == null || contents.isBlank()) ? "메시지가 없습니다." : contents;
    }


    @Transactional
    public void exit(Long roomIdx, Long userIdx) {
        User user = userRepository.findById(userIdx)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 사용자입니다. idx: " + userIdx));

        List<Long> affectedUserIds = participantsRepository.findAllByChatRoomsIdx(roomIdx).stream()
                .map(p -> p.getUsers().getIdx())
                .distinct()
                .toList();

        chatPresenceService.leave(roomIdx, userIdx);

        participantsRepository.deleteByChatRoomsIdxAndUsersIdx(roomIdx, userIdx);
        participantsRepository.flush();

        if (!participantsRepository.existsByChatRoomsIdx(roomIdx)) {
            chatMessageRepository.deleteAllByChatRoomsIdx(roomIdx);
            chatMessageRepository.flush();
            chatRoomRepository.deleteById(roomIdx);
        } else {
            sendSystemMessage(roomIdx, user.getName() + "님이 채팅방을 나갔습니다.", MessageType.EXIT);
        }

        chatListCacheService.evictUsers(affectedUserIds);
    }

    public boolean isMember(Long roomId, Long userIdx) {
        return participantsRepository.existsByChatRoomsIdxAndUsersIdx(roomId, userIdx);
    }

    @Transactional // 트랜잭션을 통해 Dirty Checking으로 저장합니다.
    public void updateRoomTitle(Long roomIdx, String newTitle, Long userIdx) {
        ChatParticipants participant = participantsRepository.findByChatRoomsIdxAndUsersIdx(roomIdx, userIdx)
                .orElseThrow(() -> new IllegalArgumentException("해당 채팅방 참여 정보를 찾을 수 없습니다."));

        participant.setCustomRoomName(newTitle);
        evictChatListCachesByRoom(roomIdx);
    }
    public void enterRoom(Long roomIdx, Long userIdx) {
        validateRoomMembership(roomIdx, userIdx);
        chatPresenceService.enter(roomIdx, userIdx);
    }

    public void leaveRoom(Long roomIdx, Long userIdx) {
        validateRoomMembership(roomIdx, userIdx);
        chatPresenceService.leave(roomIdx, userIdx);
    }

    public void refreshRoomPresence(Long roomIdx, Long userIdx) {
        validateRoomMembership(roomIdx, userIdx);
        chatPresenceService.refresh(roomIdx, userIdx);
    }
    public boolean isActiveInRoom(Long roomIdx, Long userIdx) {
        return chatPresenceService.isActiveInRoom(roomIdx, userIdx);
    }
    public void evictChatListCachesByRoom(Long roomIdx) {
        List<Long> userIds = participantsRepository.findAllByChatRoomsIdx(roomIdx).stream()
                .map(p -> p.getUsers().getIdx())
                .distinct()
                .toList();

        chatListCacheService.evictUsers(userIds);
    }

    public void evictChatListCachesByUsers(Collection<Long> userIds) {
        chatListCacheService.evictUsers(userIds);
    }
    private void validateRoomMembership(Long roomIdx, Long userIdx) {
        if (!participantsRepository.existsByChatRoomsIdxAndUsersIdx(roomIdx, userIdx)) {
            throw new IllegalArgumentException("해당 채팅방 접근 권한이 없습니다.");
        }
    }


}
