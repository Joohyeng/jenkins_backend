package com.example.WaffleBear.config.realtime;

import com.example.WaffleBear.config.sse.SseRedisSubscriber;
import com.example.WaffleBear.config.sse.SseService;
import com.example.WaffleBear.config.stomp.ClusteredStompPublisher;
import com.example.WaffleBear.config.stomp.StompRedisSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RealtimeRedisConfig {

    @Bean
    public RedisMessageListenerContainer realtimeRedisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            StompRedisSubscriber stompRedisSubscriber,
            SseRedisSubscriber sseRedisSubscriber
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(stompRedisSubscriber, new ChannelTopic(ClusteredStompPublisher.CHANNEL));
        container.addMessageListener(sseRedisSubscriber, new ChannelTopic(SseService.CHANNEL));
        return container;
    }
}
