package com.rag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolParameters;
import dev.langchain4j.agent.tool.ToolSpecification;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * Thin wrapper around the MCP Java SDK. Connects (lazily, with retry) to the mcp-atlassian
 * sidecar over streamable HTTP, exposes its tools as LangChain4j ToolSpecifications, and
 * executes tool calls. If MCP is disabled or unreachable the app keeps working (RAG-only).
 */
@Slf4j
@Service
public class McpToolClient {

    @Value("${mcp.enabled:false}")
    private boolean enabled;

    @Value("${mcp.base-url:http://mcp-atlassian:9000}")
    private String baseUrl;

    @Value("${mcp.endpoint:/mcp}")
    private String endpoint;

    @Value("${mcp.request-timeout-seconds:60}")
    private int timeoutSeconds;

    /** Read-only allow-list. Anything the server exposes beyond this is invisible to the LLM. */
    @Value("${mcp.allowed-tools:confluence_search,confluence_get_page,confluence_get_page_children}")
    private List<String> allowedTools;

    @Value("${mcp.max-result-chars:8000}")
    private int maxResultChars;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile McpSyncClient client;
    private volatile List<ToolSpecification> specs = List.of();
    private volatile Set<String> toolNames = Set.of();

    public boolean isEnabled() {
        return enabled;
    }

    public boolean hasTool(String name) {
        return toolNames.contains(name);
    }

    /** Tool specs to hand to the LLM. Empty list if MCP is off/unreachable. */
    public List<ToolSpecification> getToolSpecifications() {
        if (!enabled) return List.of();
        ensureConnected();
        return specs;
    }

    public String callTool(String name, Map<String, Object> args) {
        McpSyncClient c = client;
        if (c == null) throw new IllegalStateException("MCP client not connected");
        if (!allowedTools.contains(name)) throw new IllegalArgumentException("Tool not allowed: " + name);
        try {
            McpSchema.CallToolResult result = c.callTool(new McpSchema.CallToolRequest(name, args));
            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent t) sb.append(t.text()).append('\n');
                }
            }
            String text = sb.toString().trim();
            if (text.length() > maxResultChars) {
                text = text.substring(0, maxResultChars) + "\n...[truncated]";
            }
            return Boolean.TRUE.equals(result.isError()) ? "TOOL ERROR: " + text : text;
        } catch (RuntimeException e) {
            reset();   // force reconnect on next request (e.g. sidecar restarted)
            throw e;
        }
    }

    // ── connection management ────────────────────────────────────────────────
    private synchronized void ensureConnected() {
        if (client != null) return;
        try {
            var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .endpoint(endpoint)
                    .build();
            McpSyncClient c = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            c.initialize();

            List<ToolSpecification> list = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (McpSchema.Tool tool : c.listTools().tools()) {
                if (!allowedTools.contains(tool.name())) continue;
                list.add(toSpec(tool));
                names.add(tool.name());
            }
            this.client = c;
            this.specs = List.copyOf(list);
            this.toolNames = Set.copyOf(names);
            log.info("MCP connected to {}{} — exposing tools: {}", baseUrl, endpoint, names);
        } catch (Exception e) {
            log.warn("MCP unavailable ({}{}): {} — continuing without MCP tools", baseUrl, endpoint, e.toString());
            this.specs = List.of();
            this.toolNames = Set.of();
        }
    }

    private synchronized void reset() {
        try { if (client != null) client.closeGracefully(); } catch (Exception ignored) { }
        client = null;
        specs = List.of();
        toolNames = Set.of();
    }

    @SuppressWarnings("unchecked")
    private ToolSpecification toSpec(McpSchema.Tool tool) {
        Map<String, Map<String, Object>> props = new LinkedHashMap<>();
        McpSchema.JsonSchema schema = tool.inputSchema();
        List<String> required = List.of();
        if (schema != null) {
            if (schema.properties() != null) {
                schema.properties().forEach((k, v) ->
                        props.put(k, v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("type", "string")));
            }
            if (schema.required() != null) required = schema.required();
        }
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(ToolParameters.builder()
                        .type("object")
                        .properties(props)
                        .required(required)
                        .build())
                .build();
    }
}
