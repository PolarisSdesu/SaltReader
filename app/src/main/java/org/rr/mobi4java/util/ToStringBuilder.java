package org.rr.mobi4java.util;

import java.lang.reflect.Array;

/**
 * Minimal, dependency-free replacement for {@code commons-lang3}'s ToStringBuilder.
 *
 * <p>Supports only the {@code new ToStringBuilder(this).append(name, value)...toString()}
 * pattern used by the vendored mobi-api4java classes. Arrays are rendered like
 * commons-lang3 renders them ({@code {a,b,c}}); the resulting string is only used
 * for debugging and logging.</p>
 *
 * <p>This class was added while removing the Apache Commons dependencies from the
 * vendored code; it is not part of upstream mobi-api4java.</p>
 */
public final class ToStringBuilder {

	private final Object object;

	private final StringBuilder buffer = new StringBuilder();

	public ToStringBuilder(Object object) {
		this.object = object;
	}

	public ToStringBuilder append(String fieldName, Object value) {
		if (buffer.length() > 0) {
			buffer.append(',');
		}
		buffer.append(fieldName).append('=').append(format(value));
		return this;
	}

	private static String format(Object value) {
		if (value == null) {
			return "null";
		}
		if (!value.getClass().isArray()) {
			return value.toString();
		}
		StringBuilder arrayBuffer = new StringBuilder("{");
		int length = Array.getLength(value);
		for (int i = 0; i < length; i++) {
			if (i > 0) {
				arrayBuffer.append(',');
			}
			Object item = Array.get(value, i);
			arrayBuffer.append(item == null ? "null" : item.toString());
		}
		return arrayBuffer.append('}').toString();
	}

	@Override
	public String toString() {
		return object.getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(object))
				+ '[' + buffer + ']';
	}
}
