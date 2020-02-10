package org.jepria.retrificator;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AccessLogReader {
  
  private AccessLogReader() {}
  
  public static final Pattern RECORD_PATTERN_DEFAULT = Pattern.compile("([^ ]+) ([^ ]+) ([^ ]+) \\[([^]]+)] \"([^\"]+)\" ([^ ]+) ([^ ]+)");
  
  public static final Pattern REQUEST_PATTERN_DEFAULT = Pattern.compile("([^ ]+) ([^ ]+) ([^ ]+)");
  
  public static final DateFormat DATE_FORMAT_DEFAULT = new SimpleDateFormat("dd/MMM/yyyy:HH:mm:ss Z", Locale.US/*because MMM is written in english*/);
  
  /**
   * For the log record format see javadoc for org.apache.catalina.valves.AbstractAccessLogValve
   * <br/>
   * Example: "10.50.132.206 - admin [07/Feb/2020:18:01:00 +0300] "POST /manager/html/undeploy?path=/Ubs HTTP/1.1" 200 309828"
   */
  public static class Record {
    public String remoteHostName;
    public String remoteLogicalUsername;
    public String remoteUser;
    public Long dateAndTime;
    public Request request;
    public String httpStatus;
    public String bytesSent;
    
    @Override
    public String toString() {
      return "AccessLogRecord{" +
              "remoteHostName='" + remoteHostName + '\'' +
              ", remoteLogicalUsername='" + remoteLogicalUsername + '\'' +
              ", remoteUser='" + remoteUser + '\'' +
              ", dateAndTime='" + dateAndTime + '\'' +
              ", request='" + request + '\'' +
              ", httpStatus='" + httpStatus + '\'' +
              ", bytesSent='" + bytesSent + '\'' +
              '}';
    }
  }
  
  /**
   * A double-quoted part of a log record
   * Example: "POST /manager/html/undeploy?path=/Ubs HTTP/1.1"
   */
  public static class Request {
    public String method;
    public String url;
    public String protocol;
    
    @Override
    public String toString() {
      return "Request{" +
              "method='" + method + '\'' +
              ", url='" + url + '\'' +
              ", protocol='" + protocol + '\'' +
              '}';
    }
  }
  
  public static Record parseRecord(String s) throws IllegalArgumentException {
    return parseRecord(s, RECORD_PATTERN_DEFAULT, DATE_FORMAT_DEFAULT, REQUEST_PATTERN_DEFAULT);
  }
  
  /**
   * @param s              example input: "10.50.132.206 - admin [07/Feb/2020:18:01:00 +0300] "POST /manager/html/undeploy?path=/Ubs HTTP/1.1" 200 309828"
   * @param recordPattern
   * @param dateFormat
   * @param requestPattern
   * @return
   * @throws IllegalArgumentException
   */
  public static Record parseRecord(String s, Pattern recordPattern, DateFormat dateFormat, Pattern requestPattern) throws IllegalArgumentException {
    
    Matcher m = recordPattern.matcher(s);
    if (m.matches()) {
      
      Record record = new Record();
      record.remoteHostName = m.group(1);
      record.remoteLogicalUsername = m.group(2);
      record.remoteUser = m.group(3);
      try {
        record.dateAndTime = parseDateAndTime(m.group(4), dateFormat);
      } catch (IllegalArgumentException e) {
        // TODO throw or log? If not to throw but log instead, the API user can get enough with a partially parsed record
        throw new IllegalArgumentException("The string '" + s + "' does not match the pattern '" + recordPattern + "'", e);
      }
      try {
        record.request = parseRequest(m.group(5), requestPattern);
      } catch (IllegalArgumentException e) {
        // TODO throw or log? If not to throw but log instead, the API user can get enough with a partially parsed record
        throw new IllegalArgumentException("The string '" + s + "' does not match the pattern '" + recordPattern + "'", e);
      }
      record.httpStatus = m.group(6);
      record.bytesSent = m.group(7);
      return record;
      
    } else {
      throw new IllegalArgumentException("The input '" + s + "' does not match the pattern '" + recordPattern + "'");
    }
  }
  
  /**
   * @param s example input: "POST /manager/html/undeploy?path=/Ubs HTTP/1.1"
   * @return
   * @throws IllegalArgumentException
   */
  public static Request parseRequest(String s, Pattern requestPattern) throws IllegalArgumentException {
    Matcher m = requestPattern.matcher(s);
    if (m.matches()) {
      Request request = new Request();
      request.method = m.group(1);
      request.url = m.group(2);
      request.protocol = m.group(3);
      return request;
    } else {
      throw new IllegalArgumentException("The string '" + s + "' does not match against the pattern '" + requestPattern + "'");
    }
  }
  
  /**
   * @param s      example input: "03/Feb/2020:17:38:48 +0300"
   * @param format example:
   * @return
   * @throws IllegalArgumentException
   */
  public static Long parseDateAndTime(String s, DateFormat format) throws IllegalArgumentException {
    Date date;
    try {
      date = format.parse(s);
    } catch (ParseException e) {
      throw new IllegalArgumentException("The input '" + s + "' does not match the date format '" + format + "'");
    }
    return date.getTime();
  }
}
