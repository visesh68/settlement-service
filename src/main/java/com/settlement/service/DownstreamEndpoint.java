package com.settlement.service;

import java.net.URI;

import com.settlement.config.AppProperties;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Resolves the settlement provider's URL.
 *
 * <p>Supports a {@code {port}} token, which is substituted with the port the
 * web server actually bound to. That matters because the simulated provider is
 * served by this same application: with an ephemeral or platform-assigned port,
 * the app cannot know its own address until it has started listening.
 *
 * <p>A URL without the token is resolved eagerly and never changes — which is
 * the normal case when pointing at a real provider.
 */
@Component
public class DownstreamEndpoint implements ApplicationListener<WebServerInitializedEvent> {

    private static final String PORT_TOKEN = "{port}";

    private final String template;
    private volatile URI resolved;

    public DownstreamEndpoint(AppProperties props) {
        this.template = props.settlement().downstreamUrl();
        if (!template.contains(PORT_TOKEN)) {
            this.resolved = URI.create(template);
        }
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (template.contains(PORT_TOKEN)) {
            this.resolved = URI.create(template.replace(PORT_TOKEN, Integer.toString(event.getWebServer().getPort())));
        }
    }

    public URI uri() {
        URI uri = resolved;
        if (uri == null) {
            throw new IllegalStateException(
                    "settlement downstream URL contains " + PORT_TOKEN + " but the web server has not started yet");
        }
        return uri;
    }
}
