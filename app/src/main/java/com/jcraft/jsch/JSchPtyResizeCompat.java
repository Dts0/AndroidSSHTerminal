package com.jcraft.jsch;

/**
 * Sends one post-connect PTY window-change request with observable write
 * failures.
 *
 * <p>JSch 0.2.18's {@link ChannelShell#setPtySize(int, int, int, int)} already
 * sends this request when connected, but catches every {@link Exception}
 * internally. Its package-private {@code RequestWindowChange} also sets
 * {@code want_reply=true} whenever the channel was connected with a timeout,
 * although RFC 4254 section 6.7 recommends no reply for window changes. This
 * same-package bridge writes one standards-compatible, fire-and-forget packet
 * and lets local transport failures propagate. Initial pre-connect dimensions
 * still use {@code setPtySize()} and are carried by the normal {@code pty-req}.
 */
public final class JSchPtyResizeCompat {

    private JSchPtyResizeCompat() {}

    public static void resize(
            ChannelShell channel,
            int columns,
            int rows,
            int widthPixels,
            int heightPixels
    ) throws Exception {
        if (!channel.isConnected()) return;

        Buffer buffer = new Buffer();
        Packet packet = new Packet(buffer);
        packet.reset();
        buffer.putByte((byte) Session.SSH_MSG_CHANNEL_REQUEST);
        buffer.putInt(channel.getRecipient());
        buffer.putString(Util.str2byte("window-change"));
        buffer.putByte((byte) 0); // want_reply=false, as recommended by RFC 4254.
        buffer.putInt(columns);
        buffer.putInt(rows);
        buffer.putInt(widthPixels);
        buffer.putInt(heightPixels);
        channel.getSession().write(packet);
    }
}
