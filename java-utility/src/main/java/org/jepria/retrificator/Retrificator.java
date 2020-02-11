package org.jepria.retrificator;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public final class Retrificator {
  
  private final File retrificatorStateFile;
  private final boolean verbose;
  
  private final PrintStream logStream;
  
  /**
   * Read state from the file or create a new instance
   *
   * @return
   */
  protected RetrificatorState getState() {
    RetrificatorState state;
    if (!retrificatorStateFile.exists()) {
      state = new RetrificatorState();
    } else {
      try {
        state = RetrificatorState.deserialize(new FileReader(retrificatorStateFile));
      } catch (FileNotFoundException e) {
        e.printStackTrace(logStream);
        state = new RetrificatorState();
      }
    }
    return state;
  }
  
  protected List<String> ignoreAppNameRegexps;
  
  public List<String> getIgnoreAppNameRegexps() {
    return ignoreAppNameRegexps;
  }
  
  public void setIgnoreAppNameRegexps(List<String> ignoreAppNameRegexps) {
    this.ignoreAppNameRegexps = ignoreAppNameRegexps;
    if (verbose) {
      logStream.println("VERBOSE: setIgnoreAppNameRegexps: " + ignoreAppNameRegexps);
    }
  }
  
  public Retrificator(File retrificatorStateFile, boolean verbose, File retrificatorLogFile) {
    this.retrificatorStateFile = retrificatorStateFile;
    this.verbose = verbose;
    
    PrintStream logStream;
    if (retrificatorLogFile != null) {
      try {
        logStream = new PrintStream(new FileOutputStream(retrificatorLogFile, true), true);
      } catch (FileNotFoundException e) {
        // impossible: the file must be created
        throw new RuntimeException(e);
      }
    } else {
      logStream = System.out;
    }
    this.logStream = logStream;
  }
  
  /**
   * Retrify all tomcat webapps which have lateset access timestamp (known from the access log files) older than the age specified
   * @param tomcat
   * @param age ms
   */
  public void retrifyByAccessAge(Tomcat tomcat, long age) {
    
    if (verbose) {
      logStream.println("VERBOSE: run retrifyByAccessAge at " + new Date() + ", age: " + age + " ms");
    }
    
    final RetrificatorState state = getState();
    
    Collection<File> accessLogs = tomcat.getAccessLogs();
    Set<String> accessLogFilenames = accessLogs.stream().map(file -> file.getName()).collect(Collectors.toSet());
    
    // remove files processed which are not present anymore (deleted)
    if (verbose) {
      logStream.println("VERBOSE: state.logFilesProcessed before removing deleted log files: " + state.accessLogsProcessed);
    }
    state.accessLogsProcessed.retainAll(accessLogFilenames);
    if (verbose) {
      logStream.println("VERBOSE: state.logFilesProcessed after removing deleted log files: " + state.accessLogsProcessed);
    }
    
    // process new files
    
    for (File accessLog : accessLogs) {
      final String accessLogFilename = accessLog.getName();
      if (state.accessLogsProcessed.add(accessLogFilename)) {
        List<AccessLogReader.Record> records = new ArrayList<>();
        
        try (Scanner sc = new Scanner(accessLog)) {
          while (sc.hasNextLine()) {
            String line = sc.nextLine();
            try {
              AccessLogReader.Record record = AccessLogReader.parseRecord(line);
              records.add(record);
            } catch (IllegalArgumentException e) {
              // log and continue
              e.printStackTrace(logStream);
            }
          }
        } catch (IOException e) {
          // log and continue
          e.printStackTrace(logStream);
        }
        Map<String, Long> latestAccessMap = createLatestAccessMap(records);
        
        // merge a new map into the state's one
        for (String key : latestAccessMap.keySet()) {
          Long value = latestAccessMap.get(key);
          mergeLatestAccess(state.latestAccessMap, key, value);
        }
      }
    }
    
    // retrify
    final long now = System.currentTimeMillis();
    final long threshold = now - age;
    
    Collection<Webapp> webapps = tomcat.getWebapps();
    
    for (Webapp webapp : webapps) {
      Long latestAccess = state.latestAccessMap.get(webapp.name);
      if (latestAccess != null) {
        if (latestAccess < threshold) {
          if (ignoredApp(webapp.name)) {
            if (verbose) {
              logStream.println("VERBOSE: application retrification skipped: " + webapp.name + " (because it is in ignore list)");
            }
          } else {
            // retrify
            if (retrify(webapp)) {
              state.latestAccessMap.remove(webapp.name);
            }
          }
          
        } else {
          // do not retrify
          if (verbose) {
            logStream.println("VERBOSE: application retrification skipped: " + webapp.name + " (the application has been accessed recently)");
          }
        }
      } else {
        // TODO never retrify apps whose latest access is undefined?
        if (verbose) {
          logStream.println("VERBOSE: application retrification skipped: " + webapp.name + " (the application latest access timestamp was not found in logs)");
        }
      }
    }
    
    // remove webapps registered which are not present anymore (undeployed)
    Set<String> webappNames = webapps.stream().map(webapp -> webapp.name).collect(Collectors.toSet());
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys before removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    state.latestAccessMap.keySet().retainAll(webappNames);
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys after removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    
    // save new state
    serializeState(state);
    
    if (verbose) {
      logStream.println("VERBOSE: run complete");
    }
  }
  
  /**
   * Retrify particular webapp
   * @param webapp
   * @return
   */
  protected boolean retrify(Webapp webapp) {
    // retrify application
    
    if (webapp == null) {
      return false;
      
    } else {
      if (webapp.war == null || !webapp.war.exists()) {
        if (verbose) {
          logStream.println("VERBOSE: application retrification failed: " + webapp.name + " (the application war file could not be found)");
        }
        return false;
        
      } else {
  
        final File webappRetroFile;
        
        if (webapp.warRetro != null) {
          webappRetroFile = webapp.warRetro;
        } else {
          webappRetroFile = new File(webapp.war.getAbsolutePath() + ".retro");
        }
        
        
        if (webappRetroFile.exists()) {
          // delete the existing .war.retro file before retrifying the actual (alive) .war file
          if (!webappRetroFile.delete()) {
            // failure
            logStream.println("WARNING: failed to delete the existing retrified application by the file " + webappRetroFile + ", will try to overwrite it");
          }
        }
        
        if (webapp.war.renameTo(webappRetroFile)) {
          // success
          if (verbose) {
            logStream.println("VERBOSE: application retrification succeeded: " + webapp.name);
          }
    
          return true;
        } else {
          // failure
          logStream.println("ERROR: application retrification failed: " + webapp.name + " (failed to rename the file " + webapp.war + " to " + webappRetroFile + ")");
        }
  
        return false;
      }
    }
  }
  
  protected boolean ignoredApp(String webappName) {
    return ignoreAppNameRegexps != null && ignoreAppNameRegexps.stream().anyMatch(appNameRegex -> webappName.matches(appNameRegex));
  }
  
  /**
   * Retrify all tomcat apps which have deploy timestamp ({@link File#lastModified()}) older than the age specified
   * @param tomcat
   * @param age ms
   */
  public void retrifyByDeployAge(Tomcat tomcat, long age) {
    
    if (verbose) {
      logStream.println("VERBOSE: run retrifyByDeployAge at " + new Date() + ", age: " + age + " ms");
    }
    
    final RetrificatorState state = getState();
    
    // retrify
    final long now = System.currentTimeMillis();
    final long threshold = now - age;
    
    Collection<Webapp> webapps = tomcat.getWebapps();
    
    for (Webapp webapp : webapps) {
      if (webapp.war != null) {
        long deploy = webapp.war.lastModified();
        if (deploy < threshold) {
  
          if (ignoredApp(webapp.name)) {
            if (verbose) {
              logStream.println("VERBOSE: application retrification skipped: " + webapp.name + " (the application is in ignore list)");
            }
          } else {
            retrify(webapp);
          }
  
        } else {
          // do not retrify
          if (verbose) {
            logStream.println("VERBOSE: application retrification skipped: " + webapp.name + " (the application is not old enough)");
          }
        }
      } else {
        if (verbose) {
          logStream.println("VERBOSE: could not determine whether or not to retrify the application " + webapp.name);
        }
      }
    }
    
    // remove webapps registered which are not present anymore (undeployed)
    Set<String> webappNames = webapps.stream().map(webapp -> webapp.name).collect(Collectors.toSet());
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys before removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    state.latestAccessMap.keySet().retainAll(webappNames);
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys after removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    
    // save new state
    serializeState(state);
    
    if (verbose) {
      logStream.println("VERBOSE: run complete");
    }
  }
  
  private static void mergeLatestAccess(Map<String, Long> map, String key, Long value) {
    if (map != null) {
      Long prev = map.get(key);
      if (value != null && (prev == null || value > prev)) {
        map.put(key, value);
      }
    }
  }
  
  /**
   * @param records
   * @return Key: application context path without leading '/'; value: latest access timestamp
   */
  private Map<String, Long> createLatestAccessMap(Iterable<AccessLogReader.Record> records) {
    
    final Map<String, Long> map = new HashMap<>();
    
    if (records != null) {
      for (AccessLogReader.Record record : records) {
        
        String key;
        {
          key = record.request.url;
          while (key.length() > 0 && key.charAt(0) == '/') {
            key = key.substring(1);
          }
          
          int slashIndex = key.indexOf('/');
          key = slashIndex == -1 ? key : key.substring(0, slashIndex);
          
          int questIndex = key.indexOf('?');
          key = questIndex == -1 ? key : key.substring(0, questIndex);
        }
        
        mergeLatestAccess(map, key, record.dateAndTime);
      }
    }
    
    return map;
  }
  
  private void serializeState(RetrificatorState state) {
    try (Writer w = new FileWriter(retrificatorStateFile, false)) {
      RetrificatorState.serialize(state, w);
    } catch (IOException e) {
      e.printStackTrace(logStream);
    }
  }
}
