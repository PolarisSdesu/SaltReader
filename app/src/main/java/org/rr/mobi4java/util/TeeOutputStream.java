package org.rr.mobi4java.util;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Minimal, dependency-free replacement for {@code commons-io}'s TeeOutputStream.
 *
 * <p>Writes every byte to two underlying streams. Only the operations used by the
 * vendored mobi-api4java code are implemented (write/flush/close); the semantics
 * match commons-io: a write is forwarded to both streams in order.</p>
 *
 * <p>This class was added while removing the Apache Commons dependencies from the
 * vendored code; it is not part of upstream mobi-api4java.</p>
 */
public class TeeOutputStream extends OutputStream {

	private final OutputStream first;

	private final OutputStream second;

	public TeeOutputStream(OutputStream first, OutputStream second) {
		this.first = first;
		this.second = second;
	}

	@Override
	public void write(int b) throws IOException {
		first.write(b);
		second.write(b);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		first.write(b, off, len);
		second.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		first.flush();
		second.flush();
	}

	@Override
	public void close() throws IOException {
		IOException failure = null;
		try {
			first.close();
		} catch (IOException e) {
			failure = e;
		}
		try {
			second.close();
		} catch (IOException e) {
			if (failure == null) {
				failure = e;
			}
		}
		if (failure != null) {
			throw failure;
		}
	}
}
