package org.rr.mobi4java.exth;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.rr.mobi4java.EXTHRecord;

public class ASINRecordDelegate extends StringRecordDelegate implements RecordDelegate {

	private static final String UTF_8 = StandardCharsets.UTF_8.name();


	public ASINRecordDelegate(EXTHRecord record) {
		super(record);
	}
	
	public void setASIN(String asin) {
		try {
			if(isValidASIN(asin)) {
				setStringData(asin, UTF_8);
			} else {
				throw new IllegalArgumentException("The asin " + asin + " is not a valid asin number.");
			}
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("Failed to set asin " + asin, e);
		}
	}
	
	public String getASIN() {
		return getAsString(UTF_8);
	}
	
	private boolean isValidASIN(String asin) {
		if(asin == null) {
			return false;
		}
		return Pattern.compile("[a-zA-Z0-9]{10}").matcher(asin).matches();
	}
}
