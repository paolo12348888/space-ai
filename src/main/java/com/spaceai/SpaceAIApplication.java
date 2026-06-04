package com.spaceai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SPACE AI — Entry point Spring Boot Web Application
 * Avvia il web server Tomcat con tutti gli endpoint /api/*
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableScheduling
public class SpaceAIApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaceAIApplication.class, args);
    }
}
