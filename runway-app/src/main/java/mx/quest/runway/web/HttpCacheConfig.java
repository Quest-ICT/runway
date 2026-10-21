package mx.quest.runway.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables {@link RunwayHttpCacheProperties} so {@code PageController} and {@code SiteStaticController} can bind
 * the configured {@code Cache-Control} rules. Mirrors the sibling {@link CompressionConfig} / {@code TlsConfig}
 * pattern: one {@code @EnableConfigurationProperties} switch, no filter of its own — the header is written by
 * the controllers that build the response, not by a cross-cutting filter.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwayHttpCacheProperties.class)
public class HttpCacheConfig {
}
