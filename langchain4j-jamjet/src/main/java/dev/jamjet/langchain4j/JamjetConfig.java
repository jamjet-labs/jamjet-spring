package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.client.JamjetRuntimeClient;

public class JamjetConfig {

    private String runtimeUrl = "http://localhost:7700";
    private String apiToken;
    private String tenantId = "default";
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 120;

    public JamjetConfig runtimeUrl(String url) {
        this.runtimeUrl = url;
        return this;
    }

    public JamjetConfig apiToken(String token) {
        this.apiToken = token;
        return this;
    }

    public JamjetConfig tenantId(String id) {
        this.tenantId = id;
        return this;
    }

    public JamjetConfig connectTimeout(int seconds) {
        this.connectTimeoutSeconds = seconds;
        return this;
    }

    public JamjetConfig readTimeout(int seconds) {
        this.readTimeoutSeconds = seconds;
        return this;
    }

    public JamjetRuntimeClient buildClient() {
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtimeUrl);
        props.setApiToken(apiToken);
        props.setTenantId(tenantId);
        props.setConnectTimeoutSeconds(connectTimeoutSeconds);
        props.setReadTimeoutSeconds(readTimeoutSeconds);
        return new JamjetRuntimeClient(props);
    }

    public String getRuntimeUrl() { return runtimeUrl; }
    public String getApiToken() { return apiToken; }
    public String getTenantId() { return tenantId; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
}
