package org.jepria.retrificator;

import com.google.gson.Gson;

import java.io.Reader;
import java.io.Writer;
import java.util.*;

public class RetrificatorState {
  /**
   * Set of tomcat log filenames which had already been processed and should further be ignored
   */
  public final Set<String> logFilesProcessed = new HashSet<>();

  /**
   * Key: application context path (starts with a single '/'); value: last access timestamp
   */
  public final Map<String, Long> latestAccessMap = new HashMap<>();


  /**
   * private class for serialization purposes only
   */
  private static class StateDto {
    public Set<String> logFilesProcessed;
    public Map<String, Long> latestAccessMap;
  }

  /**
   * @param json
   * @return instance deserialized from json or new instance if the input is null or empty
   */
  public static RetrificatorState deserialize(Reader json) {
    if (json != null) {
      final String content;
      {
        try (Scanner sc = new Scanner(json)) {
          sc.useDelimiter("\\Z");
          if (sc.hasNext()) {
            content = sc.next();
          } else {
            content = null;
          }
        }
      }
      if (content != null && content.trim().length() > 0) {
        StateDto dto = new Gson().fromJson(content, StateDto.class);

        RetrificatorState state = new RetrificatorState();
        {
          if (dto.logFilesProcessed != null) {
            state.logFilesProcessed.addAll(dto.logFilesProcessed);
          }
          if (dto.latestAccessMap != null) {
            state.latestAccessMap.putAll(dto.latestAccessMap);
          }
        }
        return state;

      }
    }
    // empty or null input
    return new RetrificatorState();
  }

  public static void serialize(RetrificatorState state, Writer json) {

    StateDto dto = new StateDto();
    dto.logFilesProcessed = state.logFilesProcessed;
    dto.latestAccessMap = state.latestAccessMap;

    new Gson().toJson(dto, json);
  }

}
