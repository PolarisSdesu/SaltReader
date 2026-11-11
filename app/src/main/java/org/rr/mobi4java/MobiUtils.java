package org.rr.mobi4java;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.rr.mobi4java.EXTHRecord.RECORD_TYPE;
import org.rr.mobi4java.MobiContent.CONTENT_TYPE;
import org.rr.mobi4java.exth.DateRecordDelegate;
import org.rr.mobi4java.exth.StringRecordDelegate;

class MobiUtils {

	/**
	 * The bytes which are searched by {@link #removeRandomBytes(byte[])}. This array is deliberately kept exactly as it was in the previous
	 * implementation (including its order), because the lookup table below is built from the very same {@link Arrays#binarySearch(byte[], byte)}
	 * call to guarantee a bit-identical filtering decision.
	 * <p>
	 * Note that the array is <b>not</b> sorted, so {@link Arrays#binarySearch(byte[], byte)} only finds a subset of its values (in fact just
	 * <code>0x00</code> and <code>0x13</code>). Building the table that way intentionally preserves the legacy behavior instead of silently
	 * changing which bytes are dropped.
	 */
	private static final byte[] SEARCH_BYTES = new byte[] { 0x00, 0x14, 0x15, 0x19, 0x1c, 0x1d, 0x12, 0x13, 0x08, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07 };

	/** Lookup table indexed by the unsigned byte value: <code>true</code> if that byte is removed by {@link #removeRandomBytes(byte[])}. */
	private static final boolean[] REMOVE_BYTE = createRemoveByteLookup();

	private static boolean[] createRemoveByteLookup() {
		boolean[] lookup = new boolean[256];
		for (int i = 0; i < lookup.length; i++) {
			lookup[i] = Arrays.binarySearch(SEARCH_BYTES, (byte) i) >= 0;
		}
		return lookup;
	}

	/**
	 * Remove the replacement character which is used by utf-8 to display an unknown character (often a black diamond with a white question
	 * mark or an empty square box).
	 * 
	 * @param str The input string which should be cleaned.
	 * @return The cleaned input string.
	 */
	static String removeUtfReplacementCharacter(String str) {
		return str == null ? null : str.replace("\uFFFD", "");
	}
	
	/**
	 * Remove those bytes which are sometimes found in mobi html markup but did not fit into a string.
	 * 
	 * @param bytes The bytes which should be searched for bytes which did not belong to a string.
	 * @return A new byte array which was cleaned from non string fitting bytes or the given array itself if there was nothing to remove.
	 */
	static byte[] removeRandomBytes(byte[] bytes) {
		int keepCount = bytes.length;
		for (byte b : bytes) {
			if (REMOVE_BYTE[b & 0xff]) {
				keepCount--;
			}
		}
		if (keepCount == bytes.length) {
			return bytes;
		}
		byte[] result = new byte[keepCount];
		int index = 0;
		for (byte b : bytes) {
			if (!REMOVE_BYTE[b & 0xff]) {
				result[index++] = b;
			}
		}
		return result;
	}

	static int getTextContentStartIndex(MobiContentHeader mobiHeader) {
		int firstContentIndex = mobiHeader.getFirstContentRecordIndex();
  	if(firstContentIndex <= 0) {
  		firstContentIndex = 1; // text starts usually with at index 1
  	}
		return firstContentIndex;
	}
  
	static List<EXTHRecord> findRecordsByType(List<EXTHRecord> records, RECORD_TYPE type) {
		List<EXTHRecord> found = new ArrayList<>();
		for (EXTHRecord exthRecord : records) {
			if(exthRecord.getRecordType() == type) {
				found.add(exthRecord);
			}
		}
		return found;
	}
	
	static List<MobiContent> findContentsByType(List<MobiContent> contents, CONTENT_TYPE type) {
		List<MobiContent> result = new ArrayList<>();
		for (MobiContent content : contents) {
			if(content.getType() == type) {
				result.add(content);
			}
		}
		return result;
	}

	static int findFirstContentsIndexByType(List<MobiContent> contents, CONTENT_TYPE type) {
		for (int i = 0; i < contents.size(); i++) {
			if (contents.get(i).getType() == type) {
				return i;
			}
		}
		return -1;
	}
	
	static List<Integer> findAllContentsIndexByType(List<MobiContent> contents, CONTENT_TYPE type) {
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < contents.size(); i++) {
			if (contents.get(i).getType() == type) {
				result.add(i);
			}
		}
		return result;
	}
	
	static List<StringRecordDelegate> createStringRecords(List<EXTHRecord> records, RECORD_TYPE type) {
		List<EXTHRecord> stringRecords = MobiUtils.findRecordsByType(records, type);
		List<StringRecordDelegate> stringRecordDelegates = new ArrayList<>(stringRecords.size());
		for (EXTHRecord exthRecord : stringRecords) {
			stringRecordDelegates.add(new StringRecordDelegate(exthRecord));
		}
		return stringRecordDelegates;
	}
	
	static List<DateRecordDelegate> createDateRecords(List<EXTHRecord> records, RECORD_TYPE type) {
		List<EXTHRecord> stringRecords = MobiUtils.findRecordsByType(records, type);
		List<DateRecordDelegate> dateRecordDelegates = new ArrayList<>(stringRecords.size());
		for (EXTHRecord exthRecord : stringRecords) {
			dateRecordDelegates.add(new DateRecordDelegate(exthRecord));
		}
		return dateRecordDelegates;
	}
	
}
