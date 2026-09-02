package com.forgeops.incidents.infrastructure;

import com.forgeops.incidents.application.DetectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the incidents module's configuration properties (Phase 7 Slice 4). Enables
 * {@link DetectionProperties} ({@code forgeops.incidents.detection.*}). No beans beyond
 * property binding.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DetectionProperties.class)
class IncidentsConfiguration {
}
