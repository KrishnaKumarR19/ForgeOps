package com.forgeops.events.infrastructure.messaging;

import com.forgeops.events.application.OutboxCleanupProperties;
import com.forgeops.events.application.OutboxPublisherProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling for the outbox publisher (Phase 6 Slice 2) and retention cleanup
 * (Phase 6 Slice 4) and binds {@link OutboxPublisherProperties} and
 * {@link OutboxCleanupProperties}. Simple fixed-delay scheduling only — no Quartz, no job
 * framework, no distributed scheduler (ENGINEERING_CONSTITUTION §2.1/§2.8).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({OutboxPublisherProperties.class, OutboxCleanupProperties.class})
class SchedulingConfig {
}
