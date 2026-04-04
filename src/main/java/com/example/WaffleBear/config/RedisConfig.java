package com.example.WaffleBear.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
public class RedisConfig {

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory(
            @Value("${REDIS_SENTINEL_MASTER:}") String sentinelMaster,
            @Value("${REDIS_SENTINEL_NODES:}") String sentinelNodes,
            @Value("${REDIS_HOST:127.0.0.1}") String redisHost,
            @Value("${REDIS_PORT:6379}") int redisPort,
            @Value("${REDIS_PASSWORD:}") String redisPassword
    ) {
        if (StringUtils.hasText(sentinelMaster) && StringUtils.hasText(sentinelNodes)) {
            RedisSentinelConfiguration sentinelConfiguration = new RedisSentinelConfiguration();
            sentinelConfiguration.master(sentinelMaster.trim());

            for (String rawNode : sentinelNodes.split("[,\\s]+")) {
                if (!StringUtils.hasText(rawNode)) {
                    continue;
                }

                String[] hostAndPort = rawNode.trim().split(":");
                String host = hostAndPort[0].trim();
                int port = hostAndPort.length > 1 ? parsePort(hostAndPort[1].trim(), 26379) : 26379;
                sentinelConfiguration.sentinel(host, port);
            }

            if (StringUtils.hasText(redisPassword)) {
                sentinelConfiguration.setPassword(RedisPassword.of(redisPassword.trim()));
            }

            return new LettuceConnectionFactory(sentinelConfiguration);
        }

        RedisStandaloneConfiguration standaloneConfiguration =
                new RedisStandaloneConfiguration(redisHost.trim(), redisPort);
        if (StringUtils.hasText(redisPassword)) {
            standaloneConfiguration.setPassword(RedisPassword.of(redisPassword.trim()));
        }
        return new LettuceConnectionFactory(standaloneConfiguration);
    }

    private int parsePort(String rawPort, int fallback) {
        try {
            return Integer.parseInt(rawPort);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(objectMapper);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();

        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
