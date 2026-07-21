package com.aitestplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TestingPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestingPlatformApplication.class, args);
    }
}
