package org.jepria.retrificator;

import java.io.File;
import java.util.*;

public class TomcatImpl implements Tomcat {
  
  protected final File webappsDir;
  protected final File logsDir;
  
  public TomcatImpl(File tomcatRootDir) {
    this(new File(tomcatRootDir, "webapps"), new File(tomcatRootDir, "logs"));
  }
  
  /**
   * @param webappsDir
   * @param logsDir
   */
  public TomcatImpl(File webappsDir, File logsDir) {
    this.webappsDir = webappsDir;
    this.logsDir = logsDir;
  }
  
  @Override
  public Collection<Webapp> getWebapps() {
    
    final Collection<Webapp> webapps = new ArrayList<>();
    
    final Set<String> dirs = new HashSet<>();
    // map WebappName.war to webappName
    final Map<String, String> wars = new HashMap<>();
    // map WebappName.war.retro to webappName // XXX do not map to WebappName.war!
    final Map<String, String> retrowars = new HashMap<>();
    
    final String warExtension = ".war";
    final String retrowarExtension = ".war.retro";
    
    File[] files = webappsDir.listFiles();
    for (File file : files) {
      if (file.isDirectory()) {
        dirs.add(file.getName());
      } else if (file.isFile()) {
        String filename = file.getName();
        if (filename.length() >= warExtension.length() && filename.substring(filename.length() - warExtension.length()).equalsIgnoreCase(warExtension)) {
          String name = filename.substring(0, filename.length() - warExtension.length());
          wars.put(filename, name);
        } else if (filename.length() >= retrowarExtension.length() && filename.substring(filename.length() - retrowarExtension.length()).equalsIgnoreCase(retrowarExtension)) {
          String name = filename.substring(0, filename.length() - retrowarExtension.length());
          retrowars.put(filename, name);
        }
      }
    }
  
    {
      Iterator<String> dirIt = dirs.iterator();
      while (dirIt.hasNext()) {
        String dir = dirIt.next();
  
        final File webappDeployed = new File(webappsDir, dir);
        final String webappName = dir;
  
        // lookup war for the dir
        File webappWar = null;
        Iterator<String> warIt = wars.keySet().iterator();
        while (warIt.hasNext() && webappWar == null) {
          String war = warIt.next();
          String name = wars.get(war);
          if (webappName.equals(name)) { // case sensitive
            webappWar = new File(webappsDir, war);
            warIt.remove(); // consume war
          }
        }
  
        // lookup retrowar for the dir
        File webappRetroWar = null;
        Iterator<String> retrowarIt = retrowars.keySet().iterator();
        while (retrowarIt.hasNext() && webappRetroWar == null) {
          String retrowar = retrowarIt.next();
          String name = retrowars.get(retrowar);
          if (webappName.equals(name)) { // case sensitive
            webappRetroWar = new File(webappsDir, retrowar);
            retrowarIt.remove(); // consume retrowar
          }
        }
  
        Webapp webapp = new Webapp(webappName, webappWar, webappRetroWar, webappDeployed);
        webapps.add(webapp);
  
        dirIt.remove(); // consume dir
      }
    }
  
    {
      // now all dirs consumed, but some wars or retrowars may have retained
      Iterator<String> warIt = wars.keySet().iterator();
      while (warIt.hasNext()) {
        String war = warIt.next();
  
        final File webappWar = new File(webappsDir, war);
        final String webappName = wars.get(war); // assert not null
      
        // lookup retrowar for the war
        File webappRetroWar = null;
        Iterator<String> retrowarIt = retrowars.keySet().iterator();
        while (retrowarIt.hasNext() && webappRetroWar == null) {
          String retrowar = retrowarIt.next();
          String retrowarName = retrowars.get(retrowar);
          if (webappName.equals(retrowarName)) { // case sensitive
            webappRetroWar = new File(webappsDir, retrowar);
            retrowarIt.remove(); // consume retrowar
          }
        }
      
        Webapp webapp = new Webapp(webappName, webappWar, webappRetroWar, null);
        webapps.add(webapp);
  
        warIt.remove(); // consume war
      }
    }
  
    {
      // now all dirs and wars consumed, but some retrowars may have retained
      Iterator<String> retrowarIt = retrowars.keySet().iterator();
      while (retrowarIt.hasNext()) {
        String retrowar = retrowarIt.next();
  
        final File webappRetroWar = new File(webappsDir, retrowar);
        final String webappName = retrowars.get(retrowar); // assert not null
      
        Webapp webapp = new Webapp(webappName, null, webappRetroWar, null);
        webapps.add(webapp);
  
        retrowarIt.remove(); // consume retrowar
      }
    }
    
    return webapps;
  }
  
  @Override
  public Collection<File> getAccessLogs() {
    File[] files = logsDir.listFiles(file -> file.isFile() && file.getName().contains("_access_log."));
    return files == null ? Arrays.asList() : Arrays.asList(files);
  }
}
