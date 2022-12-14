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

import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.vertx.core.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class TLSBufferCorruptionTest {

    private static final String KEYSTORE_PATH = "config/keystore.jks";
    private static final String KEYSTORE_PASS = "secret";
    private static final boolean useSSL = true;
    private static final boolean useReadOnly = false;
    private static final int streamChunkSize = 64000;
    private static final int sliceSize = 4000;
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
        Observable<Buffer> dataStream = Observable.fromIterable(bufferList)
                // transforming the buffer into a read-only buffer fixes the problem, the slices are no longer corrupted
                .map(buffer -> useReadOnly ? Buffer.buffer(buffer.getByteBuf().asReadOnly()) : buffer)
                .concatMap(buffer -> {
                    int nrOfSlices = buffer.length() / sliceSize;
                    List<Buffer> slices = new ArrayList<>();
                    for (int i = 0; i < nrOfSlices; ++i) {
                        slices.add(buffer.slice(i * sliceSize, (i + 1) * sliceSize));
                    }
                    // append whatever remains
                    if (nrOfSlices * sliceSize < buffer.length()) {
                        slices.add(buffer.slice(nrOfSlices * sliceSize, buffer.length()));
                    }
                    int radomDelay = 100 + random.nextInt(200);
                    // the slicing bellow is purely to simulate a multicast server that is possible in case
                    // the written buffer is considered read only so it is not changed in any way by the subscriber
                    return Observable
                            .fromIterable(slices)
                            .delay(radomDelay, TimeUnit.MILLISECONDS);
                });

        vertx.createHttpServer(createServerOptions())
                .requestHandler(req -> handleRequest(req, dataStream))
                .listen(port)
                .flatMap(server -> vertx.createHttpClient(createClientOptions()).rxRequest(new RequestOptions().setMethod(GET).setHost("localhost").setPort(port)))
                .flatMap(request -> request.rxSend())
                .flatMapObservable(response -> response.toFlowable().toObservable())
                .scan(0, (index, buffer) -> checkBuffer(buffer, index, bufferList))
                .subscribe(buffer -> {}, cause -> testContext.failNow(cause), () -> testContext.completeNow());

        assertTrue(testContext.awaitCompletion(120, TimeUnit.SECONDS));
        if (testContext.failed()) {
            throw testContext.causeOfFailure();
        }
    }

    private Integer checkBuffer(Buffer buffer, Integer index, List<Buffer> bufferList) {
        for (int i = 0; i < buffer.length(); ++i) {
            assertEquals(buffer.getByte(i), getByteAt(index + i, bufferList), "Byte mismatch at index: " + (index + i));
        }
        return index + buffer.length();
    }

    private byte getByteAt(Integer index, List<Buffer> bufferList) {
        int listIndex = index / streamChunkSize;
        assertTrue(listIndex < bufferList.size());
        Buffer buffer = bufferList.get(listIndex);
        int bufferIndex = index % streamChunkSize;
        assertTrue(bufferIndex < buffer.length());
        return buffer.getByte(bufferIndex);
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
                .setKeyStoreOptions(new JksOptions()
                        .setPath(KEYSTORE_PATH)
                        .setPassword(KEYSTORE_PASS)
                );
    }

    private HttpClientOptions createClientOptions() {
        return new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setSslEngineOptions(new OpenSSLEngineOptions())
                .setSsl(useSSL)
                .setTrustAll(true)
                .setVerifyHost(false);
    }
}
