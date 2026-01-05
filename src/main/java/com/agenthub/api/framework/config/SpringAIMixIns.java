package com.agenthub.api.framework.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;

/**
 * Spring AI Message MixIns for Jackson Deserialization
 */
public class SpringAIMixIns {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class UserMessageMixIn {
        @JsonCreator
        public UserMessageMixIn(@JsonProperty("textContent") String content,
                                @JsonProperty("media") List<Media> media) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class AssistantMessageMixIn {
        @JsonCreator
        public AssistantMessageMixIn(@JsonProperty("textContent") String content) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static abstract class SystemMessageMixIn {
        @JsonCreator
        public SystemMessageMixIn(@JsonProperty("textContent") String content) {
        }
    }
}
