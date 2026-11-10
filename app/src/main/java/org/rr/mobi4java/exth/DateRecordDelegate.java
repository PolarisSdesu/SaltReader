package org.rr.mobi4java.exth;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.rr.mobi4java.EXTHRecord;

public class DateRecordDelegate extends StringRecordDelegate implements RecordDelegate {

	private static final String UTF_8 = StandardCharsets.UTF_8.name();
	
	private static final String DEFAULT_DATE_FORMAT_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ";
	
	private static final String[] DATE_PATTERN = new String[] { DEFAULT_DATE_FORMAT_PATTERN, "yyyy-MM-dd", "dd.MM.yyyy", "MM/dd/yyyy",
			"yyyy/MM/dd", "yyyy.dd.MM", "yyyy-'W'ww-d", "yyyy-MM-dd hh:mm'Z'", "yyyy-MM-dd hh:mm:Z", "EEE, dd MMM yyyy HH:mm:ss z",
			"EEE, dd MMM yyyy HH:mm z", "dd MMM yyyy HH:mm:ss z", "EEE MMM dd HH:mm:ss z yyyy", "yyyyMMddhhmmssZ", "yyyyMMddhhmmZ",
			"yyyyMMddhhmmss", "yyyyMMddhhmm", "yyyyMMddhhmmZ", "'D:'yyyyMMddHHmmZ", "yyyy" };
	
	public DateRecordDelegate(EXTHRecord record) {
		super(record);
	}

	/**
	 * Get the data of this {@link EXTHRecord} instance as date.
	 * 
	 * @return A new {@link Date} instance from the data of this {@link EXTHRecord} instance.
	 * @throws ParseException 
	 */
	public Date getAsDate() throws ParseException {
		String dateString = getAsString(UTF_8);
		for (String pattern : DATE_PATTERN) {
			SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
			format.setLenient(false);
			try {
				return format.parse(dateString);
			} catch (ParseException ignored) {
				// try the next known date pattern
			}
		}
		throw new ParseException("Unable to parse the date: " + dateString, 0);
	}

	/**
	 * Set a date as value to this {@link EXTHRecord} instance.
	 * 
	 * @param date The date used as value for this {@link EXTHRecord} instance.
	 * @throws IllegalArgumentException if the given date is <code>null</code>.
	 */
	public void setDateData(Date date) {
		if(date == null) {
			throw new IllegalArgumentException("Value must not be null.");
		}
		try {
			setStringData(new SimpleDateFormat(DEFAULT_DATE_FORMAT_PATTERN, Locale.US).format(date), UTF_8);
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("Failed to set date " + date, e);
		}
	}
}
