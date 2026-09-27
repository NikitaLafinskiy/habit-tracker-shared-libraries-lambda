package com.habittracker.lambda.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class FakeRuntimeApi implements AutoCloseable {
    private static final String BASE = "/2018-06-01/runtime";
    private static final int ACCEPTED = 202;
    private static final int OK = 200;

    record Queued(Map<String, String> headers, String body, int status) {}

    record Posted(String path, String errorType, String body) {}

    private final HttpServer server;
    private final BlockingQueue<Queued> invocations = new LinkedBlockingQueue<>();
    private final List<Posted> posted = new CopyOnWriteArrayList<>();
    private volatile int nextPostStatus = ACCEPTED;

    FakeRuntimeApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(BASE + "/invocation/next", this::serveNext);
        server.createContext(BASE + "/invocation/", this::record);
        server.createContext(BASE + "/init/error", this::record);
        server.start();
    }

    String address() {
        return server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    void enqueue(Map<String, String> headers, String body) {
        invocations.add(new Queued(headers, body, OK));
    }

    void enqueueFailure(int status) {
        invocations.add(new Queued(Map.of(), "", status));
    }

    void failNextPost(int status) {
        nextPostStatus = status;
    }

    List<Posted> posted() {
        return posted;
    }

    private void serveNext(HttpExchange exchange) throws IOException {
        Queued next;
        try {
            next = invocations.poll(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (next == null) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        byte[] payload = next.body().getBytes(StandardCharsets.UTF_8);
        next.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.sendResponseHeaders(next.status(), payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(payload);
            }
        }
        exchange.close();
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String errorType =
                exchange.getRequestHeaders().getFirst(RuntimeApiClient.ERROR_TYPE_HEADER);
        posted.add(
                new Posted(
                        exchange.getRequestURI().getPath().substring(BASE.length()),
                        errorType,
                        body));
        int status = nextPostStatus;
        nextPostStatus = ACCEPTED;
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
