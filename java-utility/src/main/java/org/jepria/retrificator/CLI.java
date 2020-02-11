package org.jepria.retrificator;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * CLI options:
 * <pre>
 * -v --verbose [true|false|<empty defaults to false>]: whether to log everything or just errors
 * -t --tomcat-root [<absolute directory path>]: tomcat root (home) directory
 * -r --retrificator-root [<absolute directory path>]: retrificator root directory
 * -a --access-age [<long>]: latest access age in milliseconds, for the apps to be retrified
 * -d --deploy-age [<long>]: latest deploy age in milliseconds, for the apps to be retrified
 * </pre>
 */
public class CLI {
  public static void main(String[] args) {

    File tomcatRoot = null;
    boolean verbose = false;
    File retrificatorRoot = null;

    long accessAge = 48 * 60 * 60 * 1000L; // 48 hours
    long deployAge = 30 * 24 * 60 * 60 * 1000L; // 30 days

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
        // peek next argument
        if (i == args.length - 1) {
          verbose = true;
        } else {
          String nextArg = args[i + 1];
          if (!nextArg.startsWith("-")) {
            i++; // consume next argument
            if ("true".equalsIgnoreCase(nextArg)) {
              verbose = true;
            } else if ("false".equalsIgnoreCase(nextArg)) {
              verbose = false;
            } else {
              throw new IllegalArgumentException("Illegal value '" + nextArg + "' for the '" + arg + "' argument");
            }
          }
        }
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
          accessAge = Long.parseLong(val);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Failed to parse the value '" + val + "' as long", e);
        }
      } else if ("-d".equals(arg) || "--deploy-age".equals(arg)) {
        i++;
        if (i >= args.length) {
          throw new IllegalArgumentException("No value provided for the '" + arg + "' argument");
        }
        String val = args[i];
        try {
          deployAge = Long.parseLong(val);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Failed to parse the value '" + val + "' as long", e);
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
    
    
    Retrificator r = new Retrificator(retrificatorStateFileInternal, verbose, retrificatorLogFileInternal);
    r.setIgnoreAppNameRegexps(ignoreAppNameRegexps);
    r.retrifyByAccessAge(tomcat, accessAge);
    r.retrifyByDeployAge(tomcat, deployAge);

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
