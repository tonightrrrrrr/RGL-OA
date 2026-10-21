package com.yizhaoqi.oaarchive.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.oaarchive.common.BizException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

/**
 * OpenAI 兼容的 Chat Completions 客户端：GPT、DeepSeek、通义千问（兼容模式）、各种中转站都走这一套。
 * 只用 JDK 自带的 HttpClient，不引入 SDK，打包后的精简运行时里已有 java.net.http 和 jdk.crypto.ec。
 */
@Component
public class AiClient {

    public record ToolCall(String id, String name, String arguments) {
    }

    /** 一轮回复：文字、要调用的工具、结束原因 */
    public record Reply(String content, List<ToolCall> toolCalls, String finishReason) {
    }

    public record TestResult(boolean ok, long latencyMs, String reply, Integer modelCount, Boolean modelListed,
                             boolean toolCalls, String message) {
    }

    private final ObjectMapper mapper;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();

    public AiClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 流式请求一轮回复。文字边到边通过 onText 推给调用方；工具调用的参数分片拼好后一起返回。
     * 连接阶段失败（还没收到任何数据）会自动重试一次；cancelled 返回 true 时停止读取。
     */
    public Reply chat(AiSettingsService.Settings s, ArrayNode messages, ArrayNode tools, Consumer<String> onText,
                      BooleanSupplier cancelled) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", s.getModel());
        body.put("stream", true);
        body.set("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.set("tools", tools);
        }
        HttpRequest req = request(s, "/chat/completions")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = send(s, req);
        try (InputStream in = resp.body()) {
            if (resp.statusCode() / 100 != 2) {
                throw httpError(resp.statusCode(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            String type = resp.headers().firstValue("Content-Type").orElse("");
            if (type.contains("application/json")) {
                // 少数中转站忽略 stream 参数，直接返回完整 JSON
                return fromCompletion(mapper.readTree(in), onText);
            }
            return readStream(in, onText, cancelled);
        } catch (IOException e) {
            if (cancelled.getAsBoolean()) {
                return new Reply("", List.of(), "cancelled");
            }
            throw networkError(e);
        }
    }

    public List<String> models(AiSettingsService.Settings s) {
        HttpResponse<InputStream> resp = send(s, request(s, "/models").GET().build());
        try (InputStream in = resp.body()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (resp.statusCode() / 100 != 2) {
                throw httpError(resp.statusCode(), text);
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode m : mapper.readTree(text).path("data")) {
                if (m.hasNonNull("id")) {
                    ids.add(m.get("id").asText());
                }
            }
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            return ids;
        } catch (IOException e) {
            throw networkError(e);
        }
    }

    /** 测试连接：拉模型列表（失败不算致命），再发一条带工具的小请求，看能不能正常回复、支不支持工具调用 */
    public TestResult test(AiSettingsService.Settings s) {
        if (blank(s.getBaseUrl()) || blank(s.getApiKey()) || blank(s.getModel())) {
            return new TestResult(false, 0, null, null, null, false, "请先填写接口地址、API Key 和模型");
        }
        Integer count = null;
        Boolean listed = null;
        try {
            List<String> ids = models(s);
            count = ids.size();
            listed = ids.contains(s.getModel());
        } catch (BizException ignored) {
            // 有的中转站不开放 /models，不影响对话
        }
        ArrayNode messages = mapper.createArrayNode();
        messages.add(message("user", "这是连接测试。请调用 ping 工具，参数 text 填 RGL。"));
        ArrayNode tools = mapper.createArrayNode();
        tools.add(tool("ping", "连接测试用的工具", Map.of("text", "string")));
        long t = System.currentTimeMillis();
        try {
            StringBuilder text = new StringBuilder();
            Reply r = chat(s, messages, tools, text::append, () -> false);
            long ms = System.currentTimeMillis() - t;
            boolean tc = !r.toolCalls().isEmpty();
            String msg = tc ? "连接成功，支持工具调用，可以让小R查询和修改数据"
                    : "连接成功，但模型没有调用工具：可以对话，查询和修改功能可能不稳定，建议换一个支持工具调用的模型";
            return new TestResult(true, ms, tc ? "调用了工具 " + r.toolCalls().get(0).name() : clip(text.toString(), 80),
                    count, listed, tc, msg);
        } catch (BizException e) {
            return new TestResult(false, System.currentTimeMillis() - t, null, count, listed, false, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 消息与工具的 JSON

    public ObjectNode message(String role, String content) {
        ObjectNode m = mapper.createObjectNode();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** 简单工具定义：参数都是可选的基本类型（string / integer / number / boolean） */
    public ObjectNode tool(String name, String description, Map<String, String> params) {
        ObjectNode props = mapper.createObjectNode();
        params.forEach((k, type) -> props.putObject(k).put("type", type));
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        return toolWithSchema(name, description, schema);
    }

    public ObjectNode toolWithSchema(String name, String description, ObjectNode schema) {
        ObjectNode t = mapper.createObjectNode();
        t.put("type", "function");
        ObjectNode fn = t.putObject("function");
        fn.put("name", name);
        fn.put("description", description);
        fn.set("parameters", schema);
        return t;
    }

    // ---------------------------------------------------------------- 内部

    private Reply readStream(InputStream in, Consumer<String> onText, BooleanSupplier cancelled) throws IOException {
        StringBuilder content = new StringBuilder();
        TreeMap<Integer, String[]> calls = new TreeMap<>(); // index → {id, name, arguments}
        String finish = null;
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (cancelled.getAsBoolean()) {
                return new Reply(content.toString(), List.of(), "cancelled");
            }
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) {
                break;
            }
            JsonNode node;
            try {
                node = mapper.readTree(data);
            } catch (IOException e) {
                continue; // 个别中转站会插入非 JSON 的心跳行
            }
            if (node.has("error")) {
                throw new BizException("模型返回错误：" + clip(errorText(node), 200));
            }
            JsonNode choice = node.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            String piece = delta.path("content").isTextual() ? delta.get("content").asText() : "";
            if (!piece.isEmpty()) {
                content.append(piece);
                onText.accept(piece);
            }
            for (JsonNode tc : delta.path("tool_calls")) {
                int idx = tc.path("index").asInt(calls.size());
                String[] c = calls.computeIfAbsent(idx, k -> new String[] {"", "", ""});
                if (tc.hasNonNull("id") && c[0].isEmpty()) {
                    c[0] = tc.get("id").asText();
                }
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name") && c[1].isEmpty()) {
                    c[1] = fn.get("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    c[2] += fn.get("arguments").asText();
                }
            }
            if (choice.hasNonNull("finish_reason")) {
                finish = choice.get("finish_reason").asText();
            }
        }
        List<ToolCall> list = new ArrayList<>();
        calls.forEach((i, c) -> {
            if (!c[1].isEmpty()) {
                list.add(new ToolCall(c[0].isEmpty() ? "call_" + i : c[0], c[1], c[2].isBlank() ? "{}" : c[2]));
            }
        });
        return new Reply(content.toString(), list, finish);
    }

    private Reply fromCompletion(JsonNode root, Consumer<String> onText) {
        if (root.has("error")) {
            throw new BizException("模型返回错误：" + clip(errorText(root), 200));
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");
        String text = msg.path("content").isTextual() ? msg.get("content").asText() : "";
        if (!text.isEmpty()) {
            onText.accept(text);
        }
        List<ToolCall> list = new ArrayList<>();
        int i = 0;
        for (JsonNode tc : msg.path("tool_calls")) {
            JsonNode fn = tc.path("function");
            list.add(new ToolCall(tc.path("id").asText("call_" + i), fn.path("name").asText(), fn.path("arguments").asText("{}")));
            i++;
        }
        return new Reply(text, list, choice.path("finish_reason").asText(null));
    }

    private HttpRequest.Builder request(AiSettingsService.Settings s, String path) {
        return HttpRequest.newBuilder(URI.create(s.getBaseUrl() + path))
                .timeout(Duration.ofSeconds(s.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + s.getApiKey())
                .header("User-Agent", "RGL-OA/1.0");
    }

    /** 连接阶段失败重试一次（实测中转站偶尔会直接断开连接） */
    private HttpResponse<InputStream> send(AiSettingsService.Settings s, HttpRequest req) {
        HttpClient http = client(s.getProxy());
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException e) {
                throw networkError(e); // 超时不重试，免得用户等两倍时间
            } catch (IOException e) {
                last = e;
                sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException("请求被中断");
            }
        }
        throw networkError(last);
    }

    private HttpClient client(String proxy) {
        return clients.computeIfAbsent(proxy == null ? "" : proxy, p -> {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (!p.isBlank()) {
                int colon = p.lastIndexOf(':');
                b.proxy(ProxySelector.of(new InetSocketAddress(p.substring(0, colon), Integer.parseInt(p.substring(colon + 1)))));
            }
            return b.build();
        });
    }

    private BizException httpError(int status, String body) {
        String detail = "";
        try {
            detail = clip(errorText(mapper.readTree(body)), 200);
        } catch (IOException | RuntimeException e) {
            detail = clip(body.strip(), 200);
        }
        String hint = switch (status) {
            case 401, 403 -> "API Key 无效、已过期或没有权限";
            case 404 -> "接口地址不对，或者模型名不存在";
            case 429 -> "请求太频繁，或者账户额度不足";
            case 400 -> "请求被拒绝，常见原因是模型名写错，或者该模型不支持工具调用";
            default -> status >= 500 ? "模型服务暂时不可用，请稍后再试" : "请求失败";
        };
        return new BizException(hint + "（HTTP " + status + "）" + (detail.isEmpty() ? "" : "：" + detail));
    }

    private static BizException networkError(IOException e) {
        if (e instanceof HttpConnectTimeoutException || e instanceof ConnectException) {
            return new BizException("连不上接口地址，请检查网络、地址是否正确，或者是否需要设置代理");
        }
        if (e instanceof HttpTimeoutException) {
            return new BizException("模型响应超时，可以在设置里调大超时时间，或者换一个更快的模型");
        }
        if (e instanceof SSLException) {
            return new BizException("HTTPS 证书校验失败，请确认接口地址正确；公司网络可能拦截了 HTTPS，请设置代理");
        }
        return new BizException("网络连接中断：" + (e == null ? "未知原因" : clip(String.valueOf(e.getMessage()), 120)));
    }

    private static String errorText(JsonNode node) {
        JsonNode err = node.path("error");
        if (err.isTextual()) {
            return err.asText();
        }
        if (err.hasNonNull("message")) {
            return err.get("message").asText();
        }
        return node.path("message").asText(node.toString());
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
