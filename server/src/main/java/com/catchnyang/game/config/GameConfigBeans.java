package com.catchnyang.game.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class GameConfigBeans {

    @Bean
    public GameConfig gameConfig(@Value("${game.config-path}") Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return GameConfigLoader.load(in);
        }
    }
}
