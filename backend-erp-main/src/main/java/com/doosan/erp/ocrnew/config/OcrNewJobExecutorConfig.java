package com.doosan.erp.ocrnew.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class OcrNewJobExecutorConfig {

    @Bean(name = "ocrNewJobExecutor")
    public Executor ocrNewJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ocr-new-job-");
        executor.initialize();
        return executor;
    }
}
