package dev.jamjet.langchain4j;

import dev.jamjet.spring.JamjetProperties;
import dev.jamjet.spring.RuntimeErrorMode;
import dev.jamjet.spring.client.JamjetRuntimeClient;

public class JamjetConfig {

    private String runtimeUrl = "http://localhost:7700";
    private String apiToken;
    private String tenantId;
    private int connectTimeoutSeconds = 10;
    private int readTimeoutSeconds = 120;
    private RuntimeErrorMode onRuntimeError = RuntimeErrorMode.FAIL_CLOSED;
    private String memoryNamespace;

    public JamjetConfig runtimeUrl(String url) {
        this.runtimeUrl = url;
        return this;
    }

    public JamjetConfig apiToken(String token) {
        this.apiToken = token;
        return this;
    }

    /**
     * @deprecated ignored: the runtime takes the tenant from the API token
     *     ({@code default} in dev mode). Kept so existing code compiles.
     */
    @Deprecated
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

    /**
     * What wrapped agents and the memory store do when the runtime cannot record
     * a call: {@link RuntimeErrorMode#FAIL_CLOSED} (default) throws,
     * {@link RuntimeErrorMode#FAIL_OPEN} logs and continues.
     */
    public JamjetConfig onRuntimeError(RuntimeErrorMode mode) {
        this.onRuntimeError = mode == null ? RuntimeErrorMode.FAIL_CLOSED : mode;
        return this;
    }

    /** Wrap an AI service with this config's client and error mode. */
    public <T> T wrap(T aiService, Class<T> iface) {
        return JamjetDurableAgent.wrap(aiService, iface, buildClient(), onRuntimeError);
    }

    /**
     * Namespace for chat memory, normally the application's name. Required for
     * {@link #chatMemoryStore()}: two applications on one runtime tenant with the
     * same memory id would otherwise read each other's memory.
     */
    public JamjetConfig memoryNamespace(String namespace) {
        this.memoryNamespace = namespace;
        return this;
    }

    /** A chat memory store with this config's client, error mode and namespace. */
    public JamjetChatMemoryStore chatMemoryStore() {
        if (memoryNamespace == null || memoryNamespace.isBlank()) {
            throw new IllegalStateException("set memoryNamespace(...) (normally the application's name) "
                    + "before creating a chat memory store");
        }
        return new JamjetChatMemoryStore(buildClient(), memoryNamespace, onRuntimeError);
    }

    public JamjetRuntimeClient buildClient() {
        var props = new JamjetProperties();
        props.setRuntimeUrl(runtimeUrl);
        props.setApiToken(apiToken);
        props.setConnectTimeoutSeconds(connectTimeoutSeconds);
        props.setReadTimeoutSeconds(readTimeoutSeconds);
        props.setOnRuntimeError(onRuntimeError);
        return new JamjetRuntimeClient(props);
    }

    public String getRuntimeUrl() { return runtimeUrl; }
    public String getApiToken() { return apiToken; }
    /** @deprecated ignored; see {@link #tenantId(String)}. */
    @Deprecated
    public String getTenantId() { return tenantId; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public RuntimeErrorMode getOnRuntimeError() { return onRuntimeError; }
}
