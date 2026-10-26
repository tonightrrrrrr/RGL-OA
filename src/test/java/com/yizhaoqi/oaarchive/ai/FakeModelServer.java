package com.yizhaoqi.oaarchive.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 测试用的假模型服务：按顺序回放预先写好的 SSE 回复，并记下收到的请求体，
 * 这样不用真的 API Key 也能测完整的对话流程。
 */
class FakeModelServer implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String> replies = new ArrayDeque<>();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    volatile int status = 200;
    volatile String errorBody = "{\"error\":{\"message\":\"invalid api key\"}}";
    /** 模拟不认识 reasoning_effort 参数的接口：请求里带了这个参数就返回 400 */
    volatile boolean rejectReasoning;

    FakeModelServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", ex -> write(ex, 200, "application/json",
                "{\"data\":[{\"id\":\"fake-model\"},{\"id\":\"another\"}]}"));
        server.createContext("/v1/chat/completions", this::chat);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** 加一轮纯文字回复（分成几段流式发送） */
    FakeModelServer text(String... pieces) {
        StringBuilder sb = new StringBuilder();
        for (String p : pieces) {
            sb.append(chunk("{\"content\":" + quote(p) + "}", null));
        }
        sb.append(chunk("{}", "stop")).append("data: [DONE]\n\n");
        replies.add(sb.toString());
        return this;
    }

    /** 加一轮先输出思考过程（reasoning_content）、再输出文字的回复 */
    FakeModelServer thinking(String thought, String answer) {
        replies.add(chunk("{\"reasoning_content\":" + quote(thought) + "}", null)
                + chunk("{\"content\":" + quote(answer) + "}", null)
                + chunk("{}", "stop") + "data: [DONE]\n\n");
        return this;
    }

    /** 加一轮工具调用回复，参数故意拆成两段，模拟真实接口的分片 */
    FakeModelServer toolCall(String id, String name, String args) {
        int mid = args.length() / 2;
        String sb = chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":\"\"}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":" + quote(args.substring(0, mid)) + "}}]}", null)
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":" + quote(args.substring(mid)) + "}}]}", null)
                + chunk("{}", "tool_calls") + "data: [DONE]\n\n";
        replies.add(sb);
        return this;
    }

    private void chat(HttpExchange ex) throws IOException {
        String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(req);
        if (rejectReasoning && req.contains("reasoning_effort")) {
            write(ex, 400, "application/json", "{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\"}}");
            return;
        }
        if (status != 200) {
            write(ex, status, "application/json", errorBody);
            return;
        }
        String body = replies.isEmpty() ? chunk("{\"content\":\"(no more scripted replies)\"}", "stop") : replies.poll();
        write(ex, 200, "text/event-stream", body);
    }

    private static String chunk(String delta, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
                + (finish == null ? "null" : "\"" + finish + "\"") + "}]}\n\n";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void write(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
