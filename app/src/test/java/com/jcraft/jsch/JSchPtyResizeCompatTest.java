package com.jcraft.jsch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.junit.Test;

public class JSchPtyResizeCompatTest {

    @Test
    public void sendsOneFireAndForgetWindowChangePacket() throws Exception {
        RecordingSession session = new RecordingSession(null);
        ChannelShell channel = connectedChannel(session);

        JSchPtyResizeCompat.resize(channel, 132, 43, 2112, 1376);

        assertEquals(1, session.writeCount);
        Buffer payload = new Buffer(session.packetBytes);
        payload.setOffSet(5); // Packet.reset() reserves the SSH packet header.
        assertEquals(Session.SSH_MSG_CHANNEL_REQUEST, payload.getByte());
        assertEquals(0x10203040, payload.getInt());
        assertArrayEquals(Util.str2byte("window-change"), payload.getString());
        assertEquals(0, payload.getByte()); // want_reply=false even with a connect timeout.
        assertEquals(132, payload.getInt());
        assertEquals(43, payload.getInt());
        assertEquals(2112, payload.getInt());
        assertEquals(1376, payload.getInt());
        assertEquals(session.packetBytes.length, payload.getOffSet());
    }

    @Test
    public void propagatesTheOriginalSessionWriteFailure() throws Exception {
        Exception expected = new Exception("synthetic write failure");
        RecordingSession session = new RecordingSession(expected);
        ChannelShell channel = connectedChannel(session);

        try {
            JSchPtyResizeCompat.resize(channel, 80, 24, 0, 0);
            fail("Expected the Session.write failure to escape");
        } catch (Exception actual) {
            assertSame(expected, actual);
        }
        assertEquals(1, session.writeCount);
    }

    private static ChannelShell connectedChannel(RecordingSession session) {
        ChannelShell channel = new ChannelShell();
        channel.setSession(session);
        channel.setRecipient(0x10203040);
        channel.connected = true;
        // JSch's Request implementation would turn this into want_reply=true.
        channel.connectTimeout = 30_000;
        return channel;
    }

    private static final class RecordingSession extends Session {
        private final Exception writeFailure;
        private int writeCount;
        private byte[] packetBytes;

        private RecordingSession(Exception writeFailure) throws JSchException {
            super(new JSch(), "test", "localhost", 22);
            this.writeFailure = writeFailure;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        void write(Packet packet) throws Exception {
            writeCount++;
            if (writeFailure != null) throw writeFailure;
            Buffer buffer = packet.getBuffer();
            packetBytes = Arrays.copyOf(buffer.buffer, buffer.index);
        }
    }
}
