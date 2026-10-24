package cn.edu.library.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
public class DatabaseMigrationConfig {

    @Bean
    FlywayMigrationStrategy migrations() {
        return flyway -> {
            new ResourceDatabasePopulator(
                new ClassPathResource("db/schema.sql")
            ).execute(flyway.getConfiguration().getDataSource());
            flyway.migrate();
        };
    }
}
