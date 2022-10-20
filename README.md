# vertx-tls-reproducer

### Description

This project tries to demonstrate in a test how chunked encoding over TLS changes the buffer stream
provided as an input to the http response. My understanding is that this should not happen.

The structure used to set up the SSL server was taken from [this project](https://github.com/codingchili/vertx-brotli-reproducer)

### Test

`TLSBufferCorruptionTest` contains a test that creates a read only buffer stream of random bytes.
This stream is delivered as a http response that should contain the same data.


### Expected results

The test should work successfully as when `useReadOnly` is true or `useSSL` is false.

### Actual results

The test fails because the http client response does not contain the same bytes as the ones sent by the server.

### Related issues

The explanation is the same as [this](https://github.com/netty/netty/issues/11792) fixed netty issue
