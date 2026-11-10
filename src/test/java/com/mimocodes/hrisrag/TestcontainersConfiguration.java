package com.mimocodes.hrisrag;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared test database: the same pgvector image as docker-compose.yml.
 * {@code @ServiceConnection} overrides the datasource from application.yml / .env.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final DockerImageName PGVECTOR_IMAGE =
			DockerImageName.parse("pgvector/pgvector:0.8.7-pg18-bookworm").asCompatibleSubstituteFor("postgres");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE);
	}
}
