package fatum.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Background work of the verification, which today is the face comparison of a new profile picture.
 *
 * <p>The pool is deliberately small: every task spends a call to Rekognition, and a queue that grows
 * faster than it drains means the service is receiving more picture changes than it should. A bounded
 * queue with a caller that waits is the honest behaviour, not an unbounded one that loses tasks.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("verificationExecutor")
    public Executor verificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("verification-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}