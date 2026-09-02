package com.forgeops.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers RabbitMQ configuration for the outbox-publisher integration tests
 * (Phase 6 Slice 2). Provides a real broker so publish/confirm behavior is exercised against
 * RabbitMQ, not mocks. The {@code @ServiceConnection} bean wires Spring AMQP's connection
 * factory to the container automatically — no committed credentials, no developer-local broker.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitMqTestContainer {

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitMqContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));
    }
}
