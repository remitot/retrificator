package org.jepria.retrificator;

import com.google.gson.Gson;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

public final class Retrificator {
  
  private final Tomcat tomcat;
  
  private final File retrificatorStateFile;
  private final boolean verbose;
  
  private final PrintStream logStream;
  
  protected static class State {
    /**
     * Set of tomcat access log filenames which had already been processed and should further be ignored
     */
    public final Set<String> accessLogsProcessed = new HashSet<>();
    
    /**
     * Key: application context path (starts with a single '/'); value: latest application access timestamp
     */
    public final Map<String, Long> latestAccessMap = new HashMap<>();
  }
  
  /**
   * class for serialization purposes only
   */
  private static class StateDto {
    public Set<String> logFilesProcessed;
    public Map<String, Long> latestAccessMap;
  }
  
  /**
   * @param json
   * @return instance deserialized from json or new instance if the input is null or empty
   */
  protected static State deserializeState(Reader json) {
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
        
        State state = new State();
        {
          if (dto.logFilesProcessed != null) {
            state.accessLogsProcessed.addAll(dto.logFilesProcessed);
          }
          if (dto.latestAccessMap != null) {
            state.latestAccessMap.putAll(dto.latestAccessMap);
          }
        }
        return state;
        
      }
    }
    // empty or null input
    return new State();
  }
  
  public static void serializeState(State state, Writer json) {
    
    StateDto dto = new StateDto();
    dto.logFilesProcessed = state.accessLogsProcessed;
    dto.latestAccessMap = state.latestAccessMap;
    
    new Gson().toJson(dto, json);
  }
  
  /**
   * Read state from the file or create a new instance
   *
   * @return
   */
  protected State getState() {
    State state;
    if (!retrificatorStateFile.exists()) {
      state = new State();
    } else {
      try {
        state = deserializeState(new FileReader(retrificatorStateFile));
      } catch (FileNotFoundException e) {
        e.printStackTrace(logStream);
        state = new State();
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
  
  /**
   * log warnings about webapps which have only a deployed directory (but no war file)
   */
  public void warnUnboundWebapps() {
    Collection<Webapp> webapps = tomcat.getWebapps();
    for (Webapp webapp : webapps) {
      if (webapp.deployed != null && webapp.war == null) {
        logStream.println("WARNING: warnUnwaredWebapps: The application " + webapp.name + " has only a deployed directory (but no war file) so it will never be retrified");
      }
    }
  }
  
  /**
   * @param tomcat                the target tomcat instance
   * @param retrificatorStateFile
   * @param verbose
   * @param retrificatorLogFile
   */
  public Retrificator(Tomcat tomcat, File retrificatorStateFile, boolean verbose, File retrificatorLogFile) {
    this.tomcat = tomcat;
    
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
  
  public static final class Strategy {
    protected Long accessAge;
    protected Long deployAge;
    protected boolean cleanupOrphanRetroWars = true;
    protected boolean cleanupState = true;
    
    protected Strategy() {}
    
    public static StrategyBuilder newBuilder() {
      return new StrategyBuilderImpl();
    }
  
    @Override
    public String toString() {
      return "Strategy{" +
              "accessAge=" + accessAge +
              ", deployAge=" + deployAge +
              ", cleanupOrphanRetroWars=" + cleanupOrphanRetroWars +
              ", cleanupState=" + cleanupState +
              '}';
    }
  }
  
  public interface StrategyBuilder {
    StrategyBuilder byAccessAge(long age);
    
    StrategyBuilder byDeployAge(long age);
  
    /**
     * delete the existing .war.retro files for applications having actual (alive) .war files
     * @param whether
     * @return
     */
    StrategyBuilder cleanupOrphanRetroWars(boolean whether);
  
    /**
     * remove webapps from the state which are not present anymore (undeployed)
     * @param whether
     * @return
     */
    StrategyBuilder cleanupState(boolean whether);
    
    Strategy create();
  }
  
  private static class StrategyBuilderImpl implements StrategyBuilder {
    protected final Strategy strategy = new Strategy();
    protected boolean built = false;
    
    @Override
    public StrategyBuilder byAccessAge(long age) {
      checkBuiltOrElseThrow();
      strategy.accessAge = age;
      return this;
    }
    
    @Override
    public StrategyBuilder byDeployAge(long age) {
      checkBuiltOrElseThrow();
      strategy.deployAge = age;
      return this;
    }
  
    @Override
    public StrategyBuilder cleanupOrphanRetroWars(boolean whether) {
      checkBuiltOrElseThrow();
      strategy.cleanupOrphanRetroWars = whether;
      return this;
    }
  
    @Override
    public StrategyBuilder cleanupState(boolean whether) {
      checkBuiltOrElseThrow();
      strategy.cleanupState = whether;
      return this;
    }
  
    @Override
    public Strategy create() {
      checkBuiltOrElseThrow();
      built = true;
      return strategy;
    }
    
    protected void checkBuiltOrElseThrow() {
      if (built) {
        throw new IllegalStateException("Already built");
      }
    }
  }
  
  
  public void retrify(Strategy strategy) {
  
    if (verbose) {
      logStream.println("VERBOSE: run retrify at " + new Date() + " with strategy: " + strategy);
    }
    
    // key: webapp name; value: webapp
    final Map<String, Webapp> webappsToRetrify = new HashMap<>();
    // element: webapp name
    final Set<String> webappsToNotRetrify = new HashSet<>();
  
    final long now = System.currentTimeMillis();
    final State state = getState();
    
  
    // perform cleanup before retrification
    if (strategy.cleanupOrphanRetroWars) {
      Collection<Webapp> webapps = tomcat.getWebapps();
      for (Webapp webapp : webapps) {
        if (webapp.war != null && webapp.war.exists() && webapp.retroWar != null && webapp.retroWar.exists()) {
          if (webapp.retroWar.delete()) {
            // success
            if (verbose) {
              logStream.println("VERBOSE: application orphan .retro.war cleanup succeeded: " + webapp.name);
            }
          } else {
            // failure
            logStream.println("ERROR: application orphan .retro.war cleanup failed: " + webapp.name + " (failed to delete the file " + webapp.retroWar + ")");
          }
        }
      }
    }
    
    if (strategy.cleanupState) {
      Collection<Webapp> webapps = tomcat.getWebapps();
      Set<String> webappNames = webapps.stream().map(webapp -> webapp.name).collect(Collectors.toSet());
      state.latestAccessMap.keySet().retainAll(webappNames);
    }
  
    Collection<Webapp> webapps = tomcat.getWebapps();
    
    if (strategy.accessAge != null) {
      // Retrify all tomcat webapps which have lateset access timestamp (known from the access log files) older than the age specified
      
      final long age = strategy.accessAge;
      
      Collection<File> accessLogs = tomcat.getAccessLogs();
      Set<String> accessLogFilenames = accessLogs.stream().map(file -> file.getName()).collect(Collectors.toSet());
      
      // remove files processed which are not present anymore (deleted)
      state.accessLogsProcessed.retainAll(accessLogFilenames);
      
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
      
      final long threshold = now - age;
      
      for (Webapp webapp : webapps) {
        if (webapp.war != null && !ignoredApp(webapp.name)) {
          Long latestAccess = state.latestAccessMap.get(webapp.name);
          if (latestAccess != null) {
            if (latestAccess < threshold) {
              // retrify
              webappsToRetrify.put(webapp.name, webapp);
            } else {
              // do not retrify
              webappsToNotRetrify.add(webapp.name);
            }
          }
        }
      }
    }
    
    if (strategy.deployAge != null) {
      // Retrify all tomcat apps which have deploy timestamp ({@link File#lastModified()}) older than the age specified
  
      final long age = strategy.deployAge;
  
      final long threshold = now - age;
  
      for (Webapp webapp : webapps) {
        if (webapp.war != null && !ignoredApp(webapp.name)) {
          long deploy = getDeployTime(webapp.war);
          if (deploy < threshold) {
            webappsToRetrify.put(webapp.name, webapp);
          } else {
            webappsToNotRetrify.add(webapp.name);
          }
        }
      }
    }
    
    // perform retrification
    for (Webapp webapp: webappsToRetrify.values()) {
      if (!webappsToNotRetrify.contains(webapp.name)) {
        if (retrify(webapp)) {
          state.latestAccessMap.remove(webapp.name);
        }
      }
    }
  
    // save new state
    serializeState(state);
  
    if (verbose) {
      logStream.println("VERBOSE: run complete");
    }
  }
  
  /**
   * Retrify particular webapp
   *
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
        
        if (webapp.retroWar != null) {
          webappRetroFile = webapp.retroWar;
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
   * Deploy timestamp is the latest timestamp over the file's creation, access and modification timestamps
   *
   * @param webappWar NotNull
   * @return
   */
  // TODO really need to check access timestamp or maybe enough with create and modify timestamps?
  private static long getDeployTime(File webappWar) {
    final BasicFileAttributes attrs;
    try {
      attrs = Files.readAttributes(webappWar.toPath(), BasicFileAttributes.class);
    } catch (IOException e) {
      throw new RuntimeException(e); // TODO maybe better to skip and log?
    }
    long created = attrs.creationTime().toMillis();
    long accessed = attrs.lastAccessTime().toMillis();
    long modified = attrs.lastModifiedTime().toMillis();
    return Math.max(Math.max(created, accessed), modified);
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
  
  private void serializeState(State state) {
    try (Writer w = new FileWriter(retrificatorStateFile, false)) {
      serializeState(state, w);
    } catch (IOException e) {
      e.printStackTrace(logStream);
    }
  }
}
