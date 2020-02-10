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

    File tomcatLogsDirInternal = new File(tomcatRoot, "logs");
    File tomcatWebappsDirInternal = new File(tomcatRoot, "webapps");

    File retrificatorStateFileInternal = new File(retrificatorRoot, "retrificator-state.json");
    File retrificatorLogFileInternal = new File(retrificatorRoot, "retrificator-log.txt");

    File retrificatorIgnoreAppsFileInternal = new File(retrificatorRoot, "ignore-apps.txt");
    List<String> ignoreAppNameRegexps = null;
    {
      if (retrificatorIgnoreAppsFileInternal.exists()) {
        ignoreAppNameRegexps = new ArrayList<>();
        try (Scanner sc = new Scanner(retrificatorIgnoreAppsFileInternal)) {
          while (sc.hasNextLine()) {
            String s = sc.nextLine();
            if (s != null) {
              String ignoreAppName = s.trim();
              if (!"".equals(ignoreAppName)) {
                ignoreAppNameRegexps.add(ignoreAppName);
              }
            }
          }
        } catch (FileNotFoundException e) {
          throw new RuntimeException(e); // impossible
        }
      }
    }

    Retrificator r = new Retrificator(tomcatWebappsDirInternal, retrificatorStateFileInternal, verbose, retrificatorLogFileInternal);
    r.setIgnoreAppNameRegexps(ignoreAppNameRegexps);
    r.retrifyByAccessAge(tomcatLogsDirInternal, accessAge);
    r.retrifyByDeployAge(deployAge);

  }
}
