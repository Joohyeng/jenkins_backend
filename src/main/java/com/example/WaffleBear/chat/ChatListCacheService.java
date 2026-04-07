package com.example.WaffleBear.chat;

import com.example.WaffleBear.chat.model.dto.ChatRoomsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatListCacheService {
    //test
    private static final Duration TTL = Duration.ofSeconds(10);

    private final RedisTemplate<String, Object> redisTemplate;

    private String cacheKey(Long userIdx, int page, int size) {
        return "chat:list:user:" + userIdx + ":page:" + page + ":size:" + size;
    }

    private String indexKey(Long userIdx) {
        return "chat:list:user:" + userIdx + ":keys";
    }

    public ChatRoomsDto.PageRes get(Long userIdx, int page, int size) {
        Object value = redisTemplate.opsForValue().get(cacheKey(userIdx, page, size));
        if (value instanceof ChatRoomsDto.PageRes pageRes) {
            return pageRes;
        }
        return null;
    }

    public void put(Long userIdx, int page, int size, ChatRoomsDto.PageRes value) {
        String key = cacheKey(userIdx, page, size);
        redisTemplate.opsForValue().set(key, value, TTL);
        redisTemplate.opsForSet().add(indexKey(userIdx), key);
        redisTemplate.expire(indexKey(userIdx), TTL.multipliedBy(2));
    }

    public void evictUser(Long userIdx) {
        String indexKey = indexKey(userIdx);
        Set<Object> rawKeys = redisTemplate.opsForSet().members(indexKey);

        if (rawKeys != null && !rawKeys.isEmpty()) {
            Set<String> keys = rawKeys.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
            redisTemplate.delete(keys);
        }

        redisTemplate.delete(indexKey);
    }


    public void evictUsers(Collection<Long> userIds) {
        if (userIds == null) return;
        userIds.stream().filter(Objects::nonNull).distinct().forEach(this::evictUser);
    }
}
