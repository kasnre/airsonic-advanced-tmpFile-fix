package org.airsonic.player.io;

import org.airsonic.player.util.FileUtil;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Subclass of {@link InputStream} which provides on-the-fly transcoding.
 * Instances of <code>TranscodeInputStream</code> can be chained together, for instance to convert
 * from OGG to WAV to MP3.
 *
 * This implementation uses memory-based pipes to avoid temporary files.
 */
public class TranscodeInputStream extends InputStream {

    private static final Logger LOG = LoggerFactory.getLogger(TranscodeInputStream.class);

    private InputStream processInputStream;
    private OutputStream processOutputStream;
    private Process process;

    /**
     * Creates a transcoded input stream by executing an external process. If <code>in</code> is not null,
     * data from it is copied to the command via a memory pipeline.
     *
     * @param processBuilder Used to create the external process.
     * @param in Data to feed to the process.  May be {@code null}.
     * @throws IOException If an I/O error occurs.
     */
    public TranscodeInputStream(ProcessBuilder processBuilder, final InputStream in) throws IOException {
        LOG.info("Starting transcoder: {}", processBuilder.command().stream().collect(Collectors.joining("][", "[", "]")));

        process = processBuilder.start();
        processOutputStream = process.getOutputStream();
        processInputStream = process.getInputStream();

        // Must read stderr from the process, otherwise it may block.
        final String name = processBuilder.command().get(0);
        new InputStreamReaderThread(process.getErrorStream(), name, true).start();

        if (in != null) {
            PipedInputStream pipedInputStream = new PipedInputStream(65536); // 64 KB buffer
            PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);

            // Write input data to the pipe
            new Thread(() -> {
                try {
                    IOUtils.copy(in, pipedOutputStream);
                } catch (IOException e) {
                    LOG.warn("Error copying input stream to pipe", e);
                } finally {
                    FileUtil.closeQuietly(in);
                    FileUtil.closeQuietly(pipedOutputStream); // Ensure the pipe is closed
                }
            }).start();

            // Write the piped input stream to the external process
            new Thread(() -> {
                try {
                    IOUtils.copy(pipedInputStream, processOutputStream);
                } catch (IOException e) {
                    LOG.warn("Error feeding pipe to process", e);
                } finally {
                    FileUtil.closeQuietly(processOutputStream); // Ensure process input is closed
                }
            }).start();
        }
    }

    /**
     * Overloaded constructor to support optional tmpFile parameter for compatibility.
     *
     * @param processBuilder Used to create the external process.
     * @param in             Data to feed to the process. May be {@code null}.
     * @param tmpFile        Temporary file (currently unused in memory-based processing).
     * @throws IOException If an I/O error occurs.
     */
    public TranscodeInputStream(ProcessBuilder processBuilder, final InputStream in, Path tmpFile) throws IOException {
        this(processBuilder, in);
        LOG.info("Compatibility constructor with tmpFile called. tmpFile path: {}", tmpFile);
        // tmpFile is ignored since this implementation avoids file-based operations
    }

    /**
     * Retrieves the associated process for external access.
     *
     * @return The underlying process.
     */
    public Process getProcess() {
        return process;
    }

    @Override
    public int read() throws IOException {
        return processInputStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return processInputStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return processInputStream.read(b, off, len);
    }

    @Override
    public void close() {
        FileUtil.closeQuietly(processInputStream);
        FileUtil.closeQuietly(processOutputStream);

        if (process != null) {
            try {
                process.waitFor(); // Ensure process has completed
            } catch (InterruptedException e) {
                LOG.warn("Process was interrupted", e);
            } finally {
                process.destroy();
            }
        }
    }
}
