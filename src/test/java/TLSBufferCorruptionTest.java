import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Observable;
import io.vertx.core.file.OpenOptions;
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
import io.vertx.rxjava3.core.file.FileSystem;
import io.vertx.rxjava3.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.vertx.core.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class TLSBufferCorruptionTest {

    private static final String KEYSTORE_PATH = "config/keystore.jks";
    private static final String KEYSTORE_PASS = "secret";
    //private static final Set<String> TLS_ENABLED_PROTOCOLS = Set.of("TLSv1.3", "TLSv1.2");
    private static final Set<String> TLS_ENABLED_PROTOCOLS = Set.of("TLSv1.1");
    private static final boolean useSSL = true;
    private static final int streamChunkSize = 17000;
    private static final int maxChunkSize = 8192;
    private static final int port = 16969;
    Vertx vertx = Vertx.vertx();

    @Test
    void test() throws Throwable {
        VertxTestContext testContext = new VertxTestContext();
        Random random = new Random();

        // build a stream of sliced read-only buffers
        Buffer zero = Buffer.buffer(new byte[]{0x00});
        FileSystem fileSystem = vertx.fileSystem();
        // the data stream is a series of sliced buffers at random length interleaved with 1 byte buffers containing 0x00
        Observable<Buffer> dataStream = fileSystem.rxOpen("data/file_in", new OpenOptions())
                .flatMapObservable(file -> file.setReadBufferSize(streamChunkSize).toObservable())
                .concatMap(buffer -> {
                    assertTrue(buffer.length() > 10);
                    // random split -> some differences
                    //int sliceAt = 2 + random.nextInt(buffer.length() - 5);
                    // split at half -> no differences as long as first half < 16k
                    //int sliceAt = buffer.length() / 2;
                    // split at 100 -> no differences because first half < 16k
                    //int sliceAt = 100;
                    // first half above 16k -> evey chunk is corrupted
                    int sliceAt = 16600 < buffer.length() ? 16600 : 10;
                    int radomDelay = 100 + random.nextInt(200);
                    // the slicing bellow is purely to simulate a multicast server that is possible in case
                    // the written buffer is considered read only so it is not changed in any way by the subscriber
                    return Observable
                            .fromIterable(Arrays.asList(
                                    buffer.slice(0, sliceAt),
                                    zero,
                                    buffer.slice(sliceAt, buffer.length()),
                                    zero))
                            .delay(radomDelay, TimeUnit.MILLISECONDS);
                });

        vertx.createHttpServer(createServerOptions())
                .requestHandler(req -> handleRequest(req, dataStream))
                .listen(port)
                .flatMap(server -> vertx.createHttpClient(createClientOptions()).rxRequest(new RequestOptions().setMethod(GET).setHost("localhost").setPort(port)))
                .flatMap(request -> request.rxSend())
                .subscribe(response -> {
                    final Flowable<Buffer> responseStream = response.toFlowable();
                    fileSystem
                            .rxOpen("data/file_out", new OpenOptions())
                            .subscribe(file ->
                                    responseStream
                                            .filter(buffer -> buffer.length() > 1) // filter out the interleaved 0x00 of size 1 (see above)
                                            .doFinally(() -> testContext.completeNow())
                                            .subscribe(file.toSubscriber()));
                });

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
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
                .setAlpnVersions(Arrays.asList(HttpVersion.HTTP_1_1))
                .setSslEngineOptions(new OpenSSLEngineOptions())
                .setEnabledSecureTransportProtocols(TLS_ENABLED_PROTOCOLS)
                .setMaxChunkSize(maxChunkSize)
                .setKeyStoreOptions(new JksOptions()
                        .setPath(KEYSTORE_PATH)
                        .setPassword(KEYSTORE_PASS)
                );
    }

    private HttpClientOptions createClientOptions() {
        return new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setSslEngineOptions(new OpenSSLEngineOptions())
                .setEnabledSecureTransportProtocols(TLS_ENABLED_PROTOCOLS)
                .setMaxChunkSize(maxChunkSize)
                .setSsl(useSSL)
                .setTrustAll(true)
                .setVerifyHost(false);
    }
}
