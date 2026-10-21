package org.jetlinks.sdk.client.example;

import io.netty.buffer.Unpooled;

import org.jetlinks.sdk.client.internal.BoundedBytes;

import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.*;

/**
 * 独立 HTTP 示例服务，从仓库根目录启动；所有语言适配器共用同一前端。
 *
 * @since 2.0
 */
public final class PlainJavaExample {
    public static void main(String[] args) throws Exception {
        ExampleConfig config =
                ExampleConfig.load(args.length == 0 ? "example.local.json" : args[0]);
        ExampleService service = new ExampleService(config);
        service.start();
        DisposableServer server =
                HttpServer.create()
                        .host(config.host)
                        .port(config.port)
                        .handle(
                                (request, response) -> {
                                    Map<String, List<String>> headers = new LinkedHashMap<>();
                                    request.requestHeaders()
                                            .names()
                                            .forEach(
                                                    n ->
                                                            headers.put(
                                                                    n,
                                                                    request.requestHeaders()
                                                                            .getAll(n)));
                                    return BoundedBytes.collect(
                                                    request.receive().asByteArray(), 1024 * 1024)
                                            .flatMap(
                                                    body ->
                                                            service.handle(
                                                                    request.method().name(),
                                                                    request.uri(),
                                                                    headers,
                                                                    body))
                                            .flatMapMany(
                                                    result -> {
                                                        response.status(result.status);
                                                        result.headers.forEach(
                                                                (k, v) -> response.header(k, v));
                                                        return response.send(
                                                                        result.body.map(
                                                                                Unpooled
                                                                                        ::wrappedBuffer))
                                                                .then();
                                                    });
                                })
                        .bindNow();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.disposeNow();
                                    service.close();
                                }));
        System.out.println("JetLinks Java Example: " + config.publicBaseUrl);
        server.onDispose().block();
    }
}
