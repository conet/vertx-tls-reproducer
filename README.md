# vertx-tls-reproducer

### Description

This project tries to demonstrate in a test how chunked encoding over TLS changes the buffer stream
provided as an input to the http response. My understanding is that this should not happen.

The structure used to set up the SSL server was taken from [this project](https://github.com/codingchili/vertx-brotli-reproducer)

### Test

`TLSBufferCorruptionTest` contains a test that creates a read only buffer stream built from stitching together
multiple buffer slices read from an input file `data/file_in` containing random bytes. This stream is delivered as a
http response which it is saved to an output file `data/file_out` that should contain the same data.

Before running anything please generate the input file using this script:

```shell
bash data/mkinput.sh
```

### Expected results

After the test is run with the current configuration the expected result is that `data/file_out` is equal to `data/file_in`

### Actual results

The files are different file out will contain several sequences of 0x0D0A (CRLF) which are probably *added to the buffer*
(thus changing it) while delivering the stream.

Here is one example:

```
0016 D420: D7 5A 9D 88 97 11 74 AC  E6 8F 68 0D 61 AF 8F 72  .Z....t. ..h.a..r  
0016 D430: 04 49 21 FD 9D B1 44 24  EA 3A B1 27 2C 0F 35 98  .I!...D$ .:.',.5.
```

```
0016 D420: D7 5A 9D 88 97 11 0D 0A  31 0D 0A 00 0D 0A 31 65  .Z...... 1.....1e  
0016 D430: 64 61 0D 0A 9D B1 44 24  EA 3A B1 27 2C 0F 35 98  da....D$ .:.',.5.  
```

Please notice this sequence `0D 0A  31 0D 0A 00 0D 0A 31 65` overwriting the input sequence of `74 AC  E6 8F 68 0D 61 AF 8F 72`.

I used [this tool](https://www.cjmweb.net/vbindiff/) to do the binary diff (can be installed with `brew install vbindiff`):

```shell
vbindiff data/file_in data/file_out
```

### Comments

All I can say is that the delivery of the http mechanism modifies the input buffer stream which should not happen as
far as I can tell. The issue does not happen if:
 * tls is disabled (see the `useSSL` flag)
 * if the stream chunk size is above 16k (TLS record size?)
 * if the buffer stream is copied (which must be avoided because of performance issues - thousands of connections consuming the same data)

### Related issues

What is happening here could be caused by a netty issue like [this](https://github.com/netty/netty/issues/11792) fixed issue
