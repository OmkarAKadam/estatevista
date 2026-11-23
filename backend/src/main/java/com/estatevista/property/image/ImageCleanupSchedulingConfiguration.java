package com.estatevista.property.image;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Enables the durable cleanup poller and its bounded non-blocking dispatcher. */
@Configuration
@EnableScheduling
public class ImageCleanupSchedulingConfiguration {

    @Bean(name = "imageCleanupTaskExecutor")
    TaskExecutor imageCleanupTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("image-cleanup-");
        executor.initialize();
        return executor;
    }
}
