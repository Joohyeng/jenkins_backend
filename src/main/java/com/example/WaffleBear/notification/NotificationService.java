package com.example.WaffleBear.notification;

import com.example.WaffleBear.config.sse.SseService;
import com.example.WaffleBear.notification.model.NotificationDto;
import com.example.WaffleBear.notification.model.NotificationEntity;
import com.example.WaffleBear.notification.model.NotificationListEntity;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.jose4j.lang.JoseException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationListRepository notificationListRepository;
    private final PushService pushService;
    private final SseService sseService;

    public NotificationService(
            NotificationRepository notificationRepository,
            NotificationListRepository notificationListRepository,
            SseService sseService
    ) throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchProviderException, InvalidKeyException {
        this.notificationRepository = notificationRepository;
        this.notificationListRepository = notificationListRepository;
        this.sseService = sseService;

        if (Security.getProperty(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        this.pushService = new PushService();
        this.pushService.setPublicKey("BLHgfPga02L2u89uc4xjhbUFTy_U04rQCjGq7o24oxtqfVmAPHTxOmp6xndSHZtGQpmt7gqTFdMXco2gRNP7_p8");
        this.pushService.setPrivateKey("pWhOI-mTyOyx5hogOmKRiYHDCtm_IMpnz1lzWNdMfKU");
        this.pushService.setSubject("mailto:no-reply@fileinnout.local");
    }

    @Transactional
    public synchronized void subscribe(NotificationDto.Subscribe dto, Long userIdx) {
        List<NotificationEntity> subscriptions = notificationRepository.findAllByEndpoint(dto.endpoint());

        if (subscriptions.isEmpty()) {
            notificationRepository.save(dto.toEntity(userIdx));
            return;
        }

        NotificationEntity existing = subscriptions.get(0);
        NotificationEntity updated = NotificationEntity.builder()
                .idx(existing.getIdx())
                .userIdx(userIdx)
                .endpoint(existing.getEndpoint())
                .p256dh(dto.keys() != null ? dto.keys().get("p256dh") : null)
                .auth(dto.keys() != null ? dto.keys().get("auth") : null)
                .isActive(true)
                .build();
        notificationRepository.save(updated);

        if (subscriptions.size() > 1) {
            notificationRepository.deleteAll(subscriptions.subList(1, subscriptions.size()));
        }
    }

    public void send(NotificationDto.Send dto)
            throws GeneralSecurityException, JoseException, IOException, ExecutionException, InterruptedException {
        NotificationEntity entity = notificationRepository.findById(dto.idx()).orElseThrow();
        Subscription.Keys keys = new Subscription.Keys(entity.getP256dh(), entity.getAuth());
        Subscription subscription = new Subscription(entity.getEndpoint(), keys);
        Notification notification = new Notification(subscription, NotificationDto.Payload.from(dto).toString());
        pushService.send(notification);
    }

    @Async
    public void sendToUser(
            Long userIdx,
            String title,
            String message,
            String lastMsg,
            Long roomIdx,
            Long unreadCount,
            String messageType
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "NEW_MESSAGE");
        payload.put("idx", userIdx);
        payload.put("title", title);
        payload.put("message", message);
        payload.put("lastMsg", lastMsg);
        payload.put("roomIdx", roomIdx != null ? roomIdx : 0L);
        payload.put("unreadCount", unreadCount != null ? unreadCount : 0L);
        payload.put("messageType", messageType);

        sseService.sendEventToUser(userIdx, "new-message", payload);
        sendPayloadToUser(userIdx, NotificationDto.Payload.create(title, message, roomIdx, unreadCount));
    }

    @Transactional
    public void unsubscribe(Long userIdx) {
        List<NotificationEntity> subscriptions = notificationRepository.findAllByUserIdx(userIdx);
        subscriptions.forEach(NotificationEntity::deactivate);
        notificationRepository.saveAll(subscriptions);
    }

    public void sendWorkspaceInviteNotification(Long receiverUserIdx, String uuid, String workspaceName) {
        createAndDispatchNotification(
                receiverUserIdx,
                "invite",
                "워크스페이스 초대",
                "[" + workspaceName + "] 워크스페이스로 초대되었습니다.",
                uuid,
                null
        );
    }

    public void sendRelationshipInviteNotification(Long receiverUserIdx, Long inviteId, String senderName) {
        createAndDispatchNotification(
                receiverUserIdx,
                "relationship_invite",
                "연결 초대",
                senderName + "님이 연결 요청을 보냈습니다.",
                null,
                inviteId
        );
    }

    public void sendGroupInviteNotification(Long receiverUserIdx, Long groupInviteId, String groupName, String senderName) {
        createAndDispatchNotification(
                receiverUserIdx,
                "group_invite",
                "그룹 초대",
                senderName + "님이 [" + groupName + "] 그룹에 초대했습니다.",
                null,
                groupInviteId
        );
    }

    public void sendGeneralNotification(Long receiverUserIdx, String title, String message) {
        createAndDispatchNotification(receiverUserIdx, "general", title, message, null, null);
    }

    public List<NotificationDto.InboxItem> getInboxNotifications(Long userIdx) {
        return notificationListRepository.findByReceiverUserIdxOrderByCreatedAtDesc(userIdx)
                .stream()
                .map(NotificationDto.InboxItem::from)
                .toList();
    }

    public void markAsRead(Long userIdx, NotificationDto.Target dto) {
        NotificationListEntity entity = findInboxTarget(userIdx, dto);
        entity.markAsRead();
        notificationListRepository.save(entity);
    }

    public void deleteNotification(Long userIdx, NotificationDto.Target dto) {
        NotificationListEntity entity = findInboxTarget(userIdx, dto);
        notificationListRepository.delete(entity);
    }

    private NotificationListEntity createAndDispatchNotification(
            Long receiverUserIdx,
            String type,
            String title,
            String message,
            String uuid,
            Long referenceId
    ) {
        NotificationListEntity inbox = NotificationListEntity.builder()
                .receiverUserIdx(receiverUserIdx)
                .uuid(uuid)
                .referenceId(referenceId)
                .type(type)
                .title(title)
                .message(message)
                .build();

        NotificationListEntity saved = notificationListRepository.save(inbox);
        sseService.sendEventToUser(receiverUserIdx, "notification", NotificationDto.Payload.fromInbox(saved));
        sendPayloadToUser(receiverUserIdx, NotificationDto.Payload.fromInbox(saved));
        return saved;
    }

    private NotificationListEntity findInboxTarget(Long userIdx, NotificationDto.Target dto) {
        if (dto.id() != null) {
            return notificationListRepository.findByIdxAndReceiverUserIdx(dto.id(), userIdx)
                    .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        }

        if (dto.uuid() != null && !dto.uuid().isBlank()) {
            return notificationListRepository.findByUuidAndReceiverUserIdx(dto.uuid(), userIdx)
                    .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        }

        throw new IllegalArgumentException("id 또는 uuid가 필요합니다.");
    }

    @Async
    private void sendPayloadToUser(Long receiverUserIdx, NotificationDto.Payload payload) {
        Map<String, NotificationEntity> uniqueSubscriptions = new LinkedHashMap<>();
        notificationRepository.findByUserIdxAndIsActiveTrue(receiverUserIdx).forEach(entity ->
                uniqueSubscriptions.putIfAbsent(entity.getEndpoint(), entity)
        );

        uniqueSubscriptions.values().forEach(entity -> {
            try {
                Subscription.Keys keys = new Subscription.Keys(entity.getP256dh(), entity.getAuth());
                Subscription subscription = new Subscription(entity.getEndpoint(), keys);
                Notification notification = new Notification(subscription, payload.toString());
                pushService.send(notification);
            } catch (Exception exception) {
                exception.printStackTrace();
            }
        });
    }
}
