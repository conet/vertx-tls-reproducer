import io.netty.buffer.ByteBuf;
import io.netty.buffer.DuplicatedByteBuf;

public class TestByteBuff extends DuplicatedByteBuf {
    public TestByteBuff(ByteBuf buffer) {
        super(buffer);
    }

    public ByteBuf ensureWritable(int minWritableBytes) {
        Thread.dumpStack();
        return super.ensureWritable(minWritableBytes);
    }
}
