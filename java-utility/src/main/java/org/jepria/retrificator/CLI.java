package org.jepria.retrificator;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI options:
 * <pre>
 * -v --verbose: whether to log everything or just errors
 * -t --tomcat-root [<absolute directory path>]: tomcat root (home) directory
 * -r --retrificator-root [<absolute directory path>]: retrificator root directory
 * -a --access-age [<long>]: latest access age in milliseconds, for the apps to be retrified. Default 14400 (10 days)
 * -d --deploy-age [<long>]: latest deploy age in milliseconds, for the apps to be retrified. Default 43200 (30 days)
 * </pre>
 */
public class CLI {
  public static void main(String[] args) {

    File tomcatRoot = null;
    boolean verbose = false;
    File retrificatorRoot = null;

    int accessAgeMins = 14400; // 10 days
    int deployAgeMins = 43200; // 30 days

    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if ("-t".equals(arg) || "--tomcat-root".equals(arg)) {
        i++;
        if (i >= args.length) {
          throw new IllegalArgumentException("No value provided for the '" + arg + "' argument");
        }
        String val = args[i];
        tomcatRoot = new File(val);

      } else if ("-v".equals(arg) || "--verbose".equals(arg)) {
        verbose = true;
      } else if ("-r".equals(arg) || "--retrificator-root".equals(arg)) {
        i++;
        if (i >= args.length) {
          throw new IllegalArgumentException("No value provided for the '" + arg + "' argument");
        }
        String val = args[i];
        retrificatorRoot = new File(val);

      } else if ("-a".equals(arg) || "--access-age".equals(arg)) {
        i++;
        if (i >= args.length) {
          throw new IllegalArgumentException("No value provided for the '" + arg + "' argument");
        }
        String val = args[i];
        try {
          accessAgeMins = Integer.parseInt(val);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Failed to parse the value '" + val + "' as integer", e);
        }
        if (accessAgeMins <= 0) {
          throw new IllegalArgumentException("Illegal value '" + val + "': positive integer allowed");
        }
      } else if ("-d".equals(arg) || "--deploy-age".equals(arg)) {
        i++;
        if (i >= args.length) {
          throw new IllegalArgumentException("No value provided for the '" + arg + "' argument");
        }
        String val = args[i];
        try {
          deployAgeMins = Integer.parseInt(val);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Failed to parse the value '" + val + "' as integer", e);
        }
        if (deployAgeMins <= 0) {
          throw new IllegalArgumentException("Illegal value '" + val + "': positive integer allowed");
        }
      }


    }


    if (tomcatRoot == null) {
      throw new IllegalStateException("Tomcat root directory not specified. Use '--tomcat-root' ('-t') option");
    }
    if (retrificatorRoot == null) {
      throw new IllegalStateException("Retrificator root directory not specified. Use '--retrificator-root' ('-r') option");
    }

    File retrificatorStateFileInternal = new File(retrificatorRoot, "retrificator-state.json");
    File retrificatorLogFileInternal = new File(retrificatorRoot, "retrificator-log.txt");

    File retrificatorIgnoreAppsFileInternal = new File(retrificatorRoot, "ignore-apps.txt");
    List<String> ignoreAppNameRegexps = readIgnoreApps(retrificatorIgnoreAppsFileInternal);
  
    Tomcat tomcat = new TomcatImpl(tomcatRoot);
    
    
    Retrificator r = new Retrificator(tomcat, retrificatorStateFileInternal, verbose, retrificatorLogFileInternal);
    r.setIgnoreAppNameRegexps(ignoreAppNameRegexps);
    r.warnUnboundWebapps();
    Retrificator.Strategy strategy = Retrificator.Strategy.newBuilder()
            .byAccessAge(accessAgeMins * 60 * 1000L)
            .byDeployAge(deployAgeMins * 60 * 1000L)
            .create();
    r.retrify(strategy);
  }

  protected static List<String> readIgnoreApps(File file) {
    final List<String> ignoreAppNameRegexps = new ArrayList<>();

    if (file.exists()) {
      try (Scanner sc = new Scanner(file)) {
        while (sc.hasNextLine()) {
          String s = sc.nextLine();
          if (s != null) {
            String sTrim = s.trim();
            if (!"".equals(sTrim) && !sTrim.startsWith("#")) {
              ignoreAppNameRegexps.add(sTrim);
            }
          }
        }
      } catch (FileNotFoundException e) {
        throw new RuntimeException(e); // impossible
      }
    }

    return ignoreAppNameRegexps;
  }
}
