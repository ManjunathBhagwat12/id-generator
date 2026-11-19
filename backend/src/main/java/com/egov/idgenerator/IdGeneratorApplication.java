package com.egov.idgenerator;

import com.egov.idgenerator.config.IdGenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(IdGenProperties.class)
public class IdGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdGeneratorApplication.class, args);
    }
}
