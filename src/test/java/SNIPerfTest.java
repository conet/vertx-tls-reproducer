import com.sun.management.OperatingSystemMXBean;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.buffer.Buffer;
import io.vertx.rxjava3.core.http.HttpClient;
import io.vertx.rxjava3.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.vertx.core.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class SNIPerfTest {

    private static final String KEYSTORE_PATH = "config/keystore.jks";
    private static final String KEYSTORE_PASS = "secret";
    private static final boolean useSSL = true;
    private static final int streamChunkSize = 640;
    private static final int port = 16969;
    Vertx vertx = Vertx.vertx();

    @Test
    void test() throws Throwable {
        VertxTestContext testContext = new VertxTestContext();
        Random random = new Random();
        final List<Buffer> bufferList = new ArrayList<>();
        for (int i = 0; i < 3; ++i) {
            Buffer data = Buffer.buffer(streamChunkSize);
            for (int j = 0; j < streamChunkSize; ++j) {
                data.setByte(j, (byte)random.nextInt(255));
            }
            bufferList.add(data);
        }

        // build a stream of sliced read-only buffers
        Observable<Buffer> dataStream = Observable.fromIterable(bufferList);

        final HttpClient client = vertx.createHttpClient(createClientOptions());
        vertx.createHttpServer(createServerOptions())
                .requestHandler(req -> handleRequest(req, dataStream))
                .listen(port)
                .flatMap(server -> Observable.range(0, 1000).flatMap(val ->
                    client.rxRequest(new RequestOptions().setMethod(GET).setHost("localhost").setPort(port))
                            .flatMap(request -> request.rxSend())
                            .onErrorComplete()
                            .flatMapObservable(response -> response.toFlowable().toObservable())).all(v -> true)
                ).subscribe(v -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
        OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        System.out.println("CPU time: " + osBean.getProcessCpuTime());
        System.out.println("CPU load: " + osBean.getProcessCpuLoad());
    }

    private void handleRequest(HttpServerRequest request, Observable<Buffer> responseStream) {
        request.response().setChunked(true);
        responseStream
                // if copy is added the bug disappears because only the copied version is altered
                //.map(Buffer::copy)
                .toFlowable(BackpressureStrategy.BUFFER)
                .subscribe(request.response().toSubscriber());
    }

    private static HttpServerOptions createServerOptions() {
        return new HttpServerOptions()
                .setSsl(useSSL)
                // comment out to break things
                //.setSni(true)
                .setAlpnVersions(Arrays.asList(HttpVersion.HTTP_1_1))
                .setSslEngineOptions(new OpenSSLEngineOptions())
                .setKeyStoreOptions(new JksOptions()
                        .setPath(KEYSTORE_PATH)
                        .setPassword(KEYSTORE_PASS)
                );
    }

    private HttpClientOptions createClientOptions() {
        return new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setSslEngineOptions(new OpenSSLEngineOptions())
                .setKeepAlive(false)
                .setSsl(useSSL)
                .setMaxPoolSize(1000)
                // comment out to break things
                //.setForceSni(true)
                .setTrustAll(true)
                .setVerifyHost(false)
                .setKeyStoreOptions(new JksOptions()
                        .setPath(KEYSTORE_PATH)
                        .setPassword(KEYSTORE_PASS)
                );
    }
}
