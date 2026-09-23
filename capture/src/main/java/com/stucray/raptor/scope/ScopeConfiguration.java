package com.stucray.raptor.scope;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring for scope. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScopeProperties.class)
class ScopeConfiguration {}
