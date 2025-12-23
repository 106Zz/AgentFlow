package com.agenthub.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agenthub")
@EnableScheduling
public class AgentHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentHubApplication.class, args);
    }

}
