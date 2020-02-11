package org.jepria.retrificator;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public final class Retrificator {
  
  private final File tomcatWebappsDir;
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
  
  public Retrificator(File tomcatWebappsDir, File retrificatorStateFile, boolean verbose, File retrificatorLogFile) {
    this.retrificatorStateFile = retrificatorStateFile;
    this.tomcatWebappsDir = tomcatWebappsDir;
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
   * Retrify all apps in {@link #tomcatWebappsDir} which have lateset access timestamp (known from the access log files) older than the age specified
   * @param tomcatLogDir directory with tomcat access log files
   * @param age
   */
  public void retrifyByAccessAge(File tomcatLogDir, long age) {
    
    if (verbose) {
      logStream.println("VERBOSE: run retrifyByAccessAge at " + new Date() + ", age: " + age + " ms");
    }
    
    final RetrificatorState state = getState();
    
    Set<String> accessLogFiles = getAccessLogFiles(tomcatLogDir);
    
    // remove files processed which are not present anymore (deleted)
    if (verbose) {
      logStream.println("VERBOSE: state.logFilesProcessed before removing deleted log files: " + state.logFilesProcessed);
    }
    state.logFilesProcessed.retainAll(accessLogFiles);
    if (verbose) {
      logStream.println("VERBOSE: state.logFilesProcessed after removing deleted log files: " + state.logFilesProcessed);
    }
    
    // process new files
    
    for (String filename : accessLogFiles) {
      if (state.logFilesProcessed.add(filename)) {
        File file = new File(tomcatLogDir, filename);
        List<AccessLogReader.Record> records = new ArrayList<>();
        
        try (Scanner sc = new Scanner(file)) {
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
    
    Set<String> webappWars = getWebappWars();
    
    for (String webapp : webappWars) {
      String webappName = webapp.substring(0, webapp.length() - ".war".length());
      
      File webappFile = new File(tomcatWebappsDir, webapp);
      Long latestAccess = state.latestAccessMap.get(webappName);
      if (latestAccess != null) {
        if (latestAccess < threshold) {
          if (ignoredApp(webappName)) {
            if (verbose) {
              logStream.println("VERBOSE: application retrification skipped: " + webappName + " (because it is in ignore list)");
            }
          } else {
            if (retrify(webappFile)) {
              state.latestAccessMap.remove(webappName);
            }
          }
          
        } else {
          // do not retrify
          if (verbose) {
            logStream.println("VERBOSE: application retrification skipped: " + webappName + " (because it has been accessed recently)");
          }
        }
      } else {
        // TODO never retrify apps whose latest access is undefined?
        if (verbose) {
          logStream.println("VERBOSE: application retrification skipped: " + webappName + " (because its latest access timestamp was not found in logs)");
        }
      }
    }
    
    // remove webapps registered which are not present anymore (undeployed)
    Set<String> webappKeys = webappWars.stream().map(war -> war.substring(0, war.length() - ".war".length())).collect(Collectors.toSet());
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys before removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    state.latestAccessMap.keySet().retainAll(webappKeys);
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
   * @param webappFile application war file
   * @return
   */
  protected boolean retrify(File webappFile) {
    // retrify application
    
    // assert webappFile exists
    if (!webappFile.exists()) {
      throw new IllegalStateException("The file " + webappFile + " expected to exist, but it is not");
    }
    
    File webappRetroFile = new File(webappFile.getAbsolutePath() + ".retro");
    
    if (webappRetroFile.exists()) {
      // delete the existing .war.retro file before retrifying the actual (alive) .war file
      if (!webappRetroFile.delete()) {
        // failure
        logStream.println("ERROR: failed to delete the existing retrified application by the file " + webappRetroFile);
      }
    }
    
    if (webappFile.renameTo(webappRetroFile)) {
      // success
      if (verbose) {
        logStream.println("VERBOSE: application retrification succeeded: " + webappFile);
      }
      
      return true;
    } else {
      // failure
      logStream.println("VERBOSE: application retrification failed: " + webappFile);
    }
    
    return false;
  }
  
  protected boolean ignoredApp(String webappName) {
    return ignoreAppNameRegexps != null && ignoreAppNameRegexps.stream().anyMatch(appNameRegex -> webappName.matches(appNameRegex));
  }
  
  /**
   * Retrify all apps in {@link #tomcatWebappsDir} which have deploy timestamp ({@link File#lastModified()}) older than the age specified
   * @param age
   */
  public void retrifyByDeployAge(long age) {
    
    if (verbose) {
      logStream.println("VERBOSE: run retrifyByDeployAge at " + new Date() + ", age: " + age + " ms");
    }
    
    final RetrificatorState state = getState();
    
    // retrify
    final long now = System.currentTimeMillis();
    final long threshold = now - age;
    
    Set<String> webappWars = getWebappWars();
    
    for (String webapp : webappWars) {
      String webappName = webapp.substring(0, webapp.length() - ".war".length());
      
      File webappFile = new File(tomcatWebappsDir, webapp);
      long deploy = webappFile.lastModified();
      if (deploy < threshold) {
        
        if (ignoredApp(webappName)) {
          if (verbose) {
            logStream.println("VERBOSE: application retrification skipped: " + webappName + " (because it is in ignore list)");
          }
        } else {
          retrify(webappFile);
        }
        
      } else {
        // do not retrify
        if (verbose) {
          logStream.println("VERBOSE: application retrification skipped: " + webappName + " (because it is not too old)");
        }
      }
    }
    
    // remove webapps registered which are not present anymore (undeployed)
    Set<String> webappKeys = webappWars.stream().map(war -> war.substring(0, war.length() - ".war".length())).collect(Collectors.toSet());
    if (verbose) {
      logStream.println("VERBOSE: state.latestAccessMap keys before removing undeployed webapps: " + state.latestAccessMap.keySet());
    }
    state.latestAccessMap.keySet().retainAll(webappKeys);
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
  
  private Set<String> getAccessLogFiles(File tomcatLogDir) {
    File[] files = tomcatLogDir.listFiles(file -> file.isFile() && file.getName().contains("_access_log."));
    final Set<String> filenames = new LinkedHashSet<>();
    if (files != null) {
      for (File file : files) {
        filenames.add(file.getName());
      }
    }
    return filenames;
  }
  
  private Set<String> getWebappWars() {
    File[] files = tomcatWebappsDir.listFiles(file -> {
      if (!file.isFile()) {
        return false;
      }
      String filename = file.getName();
      if (filename.length() >= ".war".length() && filename.substring(filename.length() - ".war".length()).equalsIgnoreCase(".war")) {
        return true;
      }
      return false;
    });
    final Set<String> filenames = new LinkedHashSet<>();
    if (files != null) {
      for (File file : files) {
        filenames.add(file.getName());
      }
    }
    return filenames;
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
