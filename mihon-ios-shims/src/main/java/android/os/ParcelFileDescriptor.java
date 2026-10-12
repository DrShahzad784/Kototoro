package android.os;

import java.io.*;

/**
 * Owned, seekable file descriptor for the PDF bridge. Raw platform descriptors are not exposed to
 * Java.
 */
public class ParcelFileDescriptor implements Closeable {
    public static final int MODE_READ_ONLY = 0x10000000,
            MODE_WRITE_ONLY = 0x20000000,
            MODE_READ_WRITE = 0x30000000,
            MODE_CREATE = 0x08000000,
            MODE_TRUNCATE = 0x04000000,
            MODE_APPEND = 0x02000000;
    private final RandomAccessFile file;
    private final File path;
    private boolean closed;

    private ParcelFileDescriptor(File path, RandomAccessFile file) {
        this.path = path;
        this.file = file;
    }

    public static ParcelFileDescriptor open(File path, int mode) throws FileNotFoundException {
        boolean readOnly = (mode & MODE_READ_WRITE) == MODE_READ_ONLY;
        if ((mode & MODE_CREATE) == 0 && !path.exists())
            throw new FileNotFoundException(path.toString());
        RandomAccessFile file = new RandomAccessFile(path, readOnly ? "r" : "rw");
        try {
            if ((mode & MODE_TRUNCATE) != 0) file.setLength(0);
            if ((mode & MODE_APPEND) != 0) file.seek(file.length());
            return new ParcelFileDescriptor(path, file);
        } catch (IOException e) {
            try {
                file.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw new FileNotFoundException(e.getMessage());
        }
    }

    public synchronized FileDescriptor getFileDescriptor() {
        check();
        try {
            return file.getFD();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized long getStatSize() {
        check();
        try {
            return file.length();
        } catch (IOException e) {
            return -1;
        }
    }

    public synchronized ParcelFileDescriptor dup() throws IOException {
        check();
        return open(path, MODE_READ_ONLY);
    }

    public synchronized byte[] kototoroReadBytes() throws IOException {
        check();
        long position = file.getFilePointer(), size = file.length();
        if (size > 256 * 1024 * 1024) throw new IOException("PDF exceeds 256 MB");
        try {
            byte[] bytes = new byte[(int) size];
            file.seek(0);
            file.readFully(bytes);
            return bytes;
        } finally {
            file.seek(position);
        }
    }

    private void check() {
        if (closed) throw new IllegalStateException("File descriptor is closed");
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            file.close();
        }
    }
}
