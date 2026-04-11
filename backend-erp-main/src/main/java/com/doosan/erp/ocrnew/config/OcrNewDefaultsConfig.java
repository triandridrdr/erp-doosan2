package com.doosan.erp.ocrnew.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

@Configuration
@PropertySource(value = "classpath:ocrnew-defaults.properties", ignoreResourceNotFound = true)
public class OcrNewDefaultsConfig {
}
