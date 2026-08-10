package com.jcraft.jsch;

/**
 * Compatibility bridge for JSch 0.2.18.
 *
 * <p>{@link ChannelShell#setPtySize(int, int, int, int)} updates the dimensions
 * used by the initial pty request, but it does not send a window-change request
 * after the channel is connected. The request implementation is package-private,
 * so this small same-package bridge exposes only the operation the app needs.
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
        channel.setPtySize(columns, rows, widthPixels, heightPixels);
        if (!channel.isConnected()) return;

        RequestWindowChange request = new RequestWindowChange();
        request.setSize(columns, rows, widthPixels, heightPixels);
        request.request(channel.getSession(), channel);
    }
}
