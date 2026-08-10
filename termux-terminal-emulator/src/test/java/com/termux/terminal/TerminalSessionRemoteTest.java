package com.termux.terminal;

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Regression coverage for TerminalSession's external-byte-stream mode. */
public class TerminalSessionRemoteTest extends TestCase {

    private static final int REMOTE_PID = -2;

    public void testRemoteSessionDoesNotUseLocalPtyAndPreservesByteRanges() {
        TestClient client = new TestClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalSession session = new TerminalSession(100, client, output::write);

        assertTrue(session.isRunning());
        assertEquals(REMOTE_PID, session.getPid());

        // Both initialization and resize must be pure JVM operations for a
        // remote session; a JNI PTY call would fail this unit test.
        session.updateSize(8, 4, 13, 15);
        session.updateSize(10, 5, 13, 15);
        assertNotNull(session.getEmulator());
        assertEquals(1, client.pidUpdates);
        assertEquals(REMOTE_PID, client.lastPid);

        byte[] framedInput = new byte[] { 0, 0x1b, '[', 'A', 0 };
        session.write(framedInput, 1, 3);
        assertBytes("byte range", "\033[A", output.toByteArray());
        output.reset();
        session.write(framedInput, 1, Integer.MAX_VALUE);
        assertEquals(0, output.size());

        int updatesBeforeOutput = client.textUpdates;
        session.appendOutput("#hi!".getBytes(StandardCharsets.UTF_8), 1, 2);
        assertEquals("hi", session.getEmulator().getScreen().getTranscriptText());
        assertEquals(updatesBeforeOutput + 1, client.textUpdates);
    }

    public void testProtocolRepliesAndPasteUseRemoteByteSink() {
        TestClient client = new TestClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalSession session = new TerminalSession(100, client, output::write);
        session.updateSize(10, 5, 13, 15);

        append(session, "\033[5n");
        assertOutput("\033[0n", output);

        append(session, "\033[6n");
        assertOutput("\033[1;1R", output);

        append(session, "\033[c");
        assertOutput("\033[?64;1;2;6;9;15;18;21;22c", output);

        append(session, "\033[?2004h");
        output.reset();
        session.getEmulator().paste("hi");
        assertOutput("\033[200~hi\033[201~", output);
    }

    public void testLargeBracketedPasteIsOneCompleteLogicalWrite() {
        TestClient client = new TestClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        final int[] writes = new int[1];
        TerminalSession session = new TerminalSession(100, client, (data, offset, count) -> {
            writes[0]++;
            output.write(data, offset, count);
        });
        session.updateSize(10, 5, 13, 15);
        append(session, "\033[?2004h");
        output.reset();
        writes[0] = 0;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30_000; i++) body.append("界");

        session.getEmulator().paste(body.toString());

        assertEquals(1, writes[0]);
        assertEquals(
            "\033[200~" + body + "\033[201~",
            new String(output.toByteArray(), StandardCharsets.UTF_8)
        );
    }

    public void testRemoteFinishIsIdempotent() {
        TestClient client = new TestClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TerminalSession session = new TerminalSession(100, client, output::write);
        session.updateSize(10, 5, 13, 15);

        session.finishIfRunning();
        session.finishIfRunning();

        assertFalse(session.isRunning());
        assertEquals(0, session.getExitStatus());
        assertEquals(1, client.finished);

        session.write("ignored".getBytes(StandardCharsets.UTF_8), 0, 7);
        assertEquals(0, output.size());
        int textUpdates = client.textUpdates;
        append(session, "ignored");
        assertEquals(textUpdates, client.textUpdates);
    }

    public void testUninitializedLocalSessionIsNotRunningOrFinished() {
        TestClient client = new TestClient();
        TerminalSession session = new TerminalSession(
            "/system/bin/sh", null, new String[0], new String[0], 100, client
        );

        assertFalse(session.isRunning());
        session.finishIfRunning();
        session.finishIfRunning();
        assertEquals(0, client.finished);
        assertEquals(0, client.pidUpdates);
    }

    private static void append(TerminalSession session, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        session.appendOutput(data, 0, data.length);
    }

    private static void assertOutput(String expected, ByteArrayOutputStream output) {
        assertBytes("output", expected, output.toByteArray());
        output.reset();
    }

    private static void assertBytes(String label, String expected, byte[] actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        assertTrue(
            label + ": expected=" + Arrays.toString(expectedBytes) +
                ", actual=" + Arrays.toString(actual),
            Arrays.equals(expectedBytes, actual)
        );
    }

    private static final class TestClient implements TerminalSessionClient {
        int textUpdates;
        int finished;
        int pidUpdates;
        int lastPid;

        @Override public void onTextChanged(TerminalSession changedSession) { textUpdates++; }
        @Override public void onTitleChanged(TerminalSession changedSession) {}
        @Override public void onSessionFinished(TerminalSession finishedSession) { finished++; }
        @Override public void onCopyTextToClipboard(TerminalSession session, String text) {}
        @Override public void onPasteTextFromClipboard(TerminalSession session) {}
        @Override public void onBell(TerminalSession session) {}
        @Override public void onColorsChanged(TerminalSession session) {}
        @Override public void onTerminalCursorStateChange(boolean state) {}
        @Override public void setTerminalShellPid(TerminalSession session, int pid) {
            pidUpdates++;
            lastPid = pid;
        }
        @Override public Integer getTerminalCursorStyle() {
            return TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;
        }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) {}
        @Override public void logStackTrace(String tag, Exception e) {}
    }
}
