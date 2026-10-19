package com.pidea;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point. @SpringBootApplication turns on component scanning of com.pidea.* and auto-configuration. */
@SpringBootApplication
@EnableScheduling
public class PideaApplication {
    public static void main(String[] args) {
        SpringApplication.run(PideaApplication.class, args);
    }
}
