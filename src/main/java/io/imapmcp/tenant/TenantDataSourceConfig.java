package io.imapmcp.tenant;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * Builds the real (Hikari-pooled) {@link DataSource} explicitly from
 * {@code spring.datasource.*}/{@code spring.datasource.hikari.*} properties
 * under its own bean name, then wraps it with {@link TenantAwareDataSource}
 * and exposes that as {@code @Primary} — so Hibernate/JPA and Flyway both
 * use the wrapped instance.
 *
 * <p>Deliberately doesn't rely on injecting Spring Boot's own
 * auto-configured {@code DataSource} bean by assumed name/qualifier: once
 * this class's wrapper bean is marked {@code @Primary}, any unqualified
 * {@code DataSource} injection point — including a guessed
 * {@code @Qualifier} on this very bean's own parameter — resolves back to
 * itself, a circular reference Spring refuses to start. Defining the real
 * datasource under an explicit, unambiguous name here avoids that entirely.
 */
@Configuration
public class TenantDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource actualDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSource actualDataSource) {
        return new TenantAwareDataSource(actualDataSource);
    }
}
