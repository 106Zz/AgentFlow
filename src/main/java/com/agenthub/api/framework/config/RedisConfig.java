package com.agenthub.api.framework.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 使用 GenericJackson2JsonRedisSerializer 自动存储类型信息
        // 解决 List<Message> 反序列化变成 LinkedHashMap 的问题
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // 关键设置：激活默认类型信息，将类名写入 JSON
        mapper.activateDefaultTyping(
            com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
            com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping.NON_FINAL,
            com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );
        // 允许访问私有字段（解决没有 Setter/Getter 的问题）
        mapper.setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.ALL, com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
        // 忽略未知属性（防止版本升级导致报错）
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // 注册 MixIn 以支持 Spring AI Message 接口的反序列化
        // 这是一个兜底策略，显式告诉 Jackson 如何处理 Message 接口
        mapper.addMixIn(org.springframework.ai.chat.messages.Message.class, MessageMixIn.class);
        mapper.addMixIn(org.springframework.ai.chat.messages.UserMessage.class, SpringAIMixIns.UserMessageMixIn.class);
        mapper.addMixIn(org.springframework.ai.chat.messages.AssistantMessage.class, SpringAIMixIns.AssistantMessageMixIn.class);
        mapper.addMixIn(org.springframework.ai.chat.messages.SystemMessage.class, SpringAIMixIns.SystemMessageMixIn.class);
        
        org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer<Object> serializer = 
                new org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer<>(mapper, Object.class);

        org.springframework.data.redis.serializer.StringRedisSerializer stringSerializer = new org.springframework.data.redis.serializer.StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }
}