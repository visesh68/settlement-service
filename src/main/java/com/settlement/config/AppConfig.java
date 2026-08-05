package com.settlement.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /**
     * Stable-per-process identity used as the outbox lease owner and stamped on
     * every log line. Two instances of this service must never share one, or
     * lease ownership checks would stop discriminating between them.
     */
    @Bean
    public InstanceId instanceId(AppProperties props) {
        String configured = props.instanceId();
        String id = (configured == null || configured.isBlank())
                ? UUID.randomUUID().toString().substring(0, 8)
                : configured;
        return new InstanceId(id);
    }

    /**
     * The drainer's HTTP client. Virtual threads carry the blocking sends, so a
     * drain pass can have many settlement calls in flight without a thread pool.
     */
    @Bean
    public HttpClient settlementHttpClient(AppProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record InstanceId(String value) {
        @Override
        public String toString() {
            return value;
        }
    }
}
