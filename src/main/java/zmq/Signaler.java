package zmq;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.Pipe;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.concurrent.atomic.AtomicLong;

import zmq.util.Errno;
import zmq.util.Utils;

//  This is a cross-platform equivalent to signal_fd. However, as opposed
//  to signal_fd there can be at most one signal in the signaler at any
//  given moment. Attempt to send a signal before receiving the previous
//  one will result in undefined behaviour.
//  这是等效于signal_fd的跨平台。 但是，与signal_fd相反，在任何给定时刻，信号器中最多可以有一个信号。
//  尝试在接收前一个信号之前发送信号将导致不确定的行为。
final class Signaler implements Closeable
{
    //  Underlying write & read file descriptor.
    //  底层的读写文件描述符。
    private final Pipe.SinkChannel   w;
    private final Pipe.SourceChannel r;
    private final Selector           selector;
    private final ByteBuffer         wdummy = ByteBuffer.allocate(1);
    private final ByteBuffer         rdummy = ByteBuffer.allocate(1);

    // Selector.selectNow at every sending message doesn't show enough performance
    // 每条发送消息中的Selector.selectNow均未显示足够的性能
    private final AtomicLong wcursor = new AtomicLong(0);
    private long             rcursor = 0;

    private final Errno errno;
    private final int   pid;
    private final Ctx   ctx;

    Signaler(Ctx ctx, int pid, Errno errno)
    {
        this.ctx = ctx;
        this.pid = pid;
        this.errno = errno;
        //  Create the socket pair for signaling.

        try {
            Pipe pipe = Pipe.open();

            r = pipe.source();
            w = pipe.sink();

            //  Set both fds to non-blocking mode.
            //  将两个 fds 都设置成 non-blocking
            Utils.unblockSocket(w, r);

            selector = ctx.createSelector();
            r.register(selector, SelectionKey.OP_READ);
        }
        catch (IOException e) {
            throw new ZError.IOException(e);
        }
    }

    @Override
    public void close() throws IOException
    {
        IOException exception = null;
        try {
            r.close();
        }
        catch (IOException e) {
            e.printStackTrace();
            exception = e;
        }
        try {
            w.close();
        }
        catch (IOException e) {
            e.printStackTrace();
            exception = e;
        }
        ctx.closeSelector(selector);
        if (exception != null) {
            throw exception;
        }
    }

    SelectableChannel getFd()
    {
        return r;
    }

    /**
     * 发送数据，但是 wdummy 为何是1。好像是假的发送
     */
    void send()
    {
        int nbytes;

        while (true) {
            try {
                wdummy.clear();
                nbytes = w.write(wdummy);
            }
            catch (IOException e) {
                throw new ZError.IOException(e);
            }
            if (nbytes == 0) {
                continue;
            }
            assert (nbytes == 1);
            wcursor.incrementAndGet();
            break;
        }
    }

    boolean waitEvent(long timeout)
    {
        int rc;
        boolean brc = (rcursor < wcursor.get());
        if (brc) {
            return true;
        }
        try {
            if (timeout == 0) {
                // waitEvent(0) is called every read/send of SocketBase
                // instant readiness is not strictly required
                // On the other hand, we can save lots of system call and increase performance
                // 并非每次都需要SocketBase的每次读/发送时都调用waitEvent（0），另一方面，我们可以节省大量系统调用并提高性能
                errno.set(ZError.EAGAIN);
                return false;

            }
            else if (timeout < 0) {
                rc = selector.select(0);
            }
            else {
                rc = selector.select(timeout);
            }
        }
        catch (ClosedSelectorException e) {
            errno.set(ZError.EINTR);
            return false;
        }
        catch (IOException e) {
            errno.set(ZError.exccode(e));
            return false;
        }

        if (rc == 0 && timeout < 0 && ! selector.keys().isEmpty()) {
            errno.set(ZError.EINTR);
            return false;
        }
        else if (rc == 0) {
            errno.set(ZError.EAGAIN);
            return false;
        }

        selector.selectedKeys().clear();

        return true;
    }

    void recv()
    {
        int nbytes = 0;
        // On windows, there may be a need to try several times until it succeeds
        // 在Windows上，可能需要尝试几次直到成功
        while (nbytes == 0) {
            try {
                rdummy.clear();
                nbytes = r.read(rdummy);
            }
            catch (ClosedChannelException e) {
                errno.set(ZError.EINTR);
                return;
            }
            catch (IOException e) {
                throw new ZError.IOException(e);
            }
        }
        assert (nbytes == 1);
        rcursor++;
    }

    @Override
    public String toString()
    {
        return "Signaler[" + pid + "]";
    }
}
