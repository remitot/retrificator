At the moment there is only a java command-line tool which runs manually.
Further plans are about binding the tool to the system service etc. to run automatically (periodically).

### java-utility
Contains java API and builds to an executable command-line tool that executes a single retrification iteration over all webapps on a Tomcat instance.

##### Example CLI usage:
`java -jar C:\apache-tomcat-7.0.68\retrificator\retrificator-1.0.0.jar -t C:\apache-tomcat-7.0.68 -r C:\apache-tomcat-7.0.68\retrificator -v`

##### CLI arguments:
- `-v --verbose [true|false|<empty defaults to false>]`: whether to log everything or just errors
- `-t --tomcat-root [<absolute directory path>]`: tomcat root (home) directory
- `-r --retrificator-root [<absolute directory path>]`: retrificator root directory
- `-a --access-age [<long>]`: latest access age in milliseconds, for the apps to be retrified
- `-d --deploy-age [<long>]`: latest deploy age in milliseconds, for the apps to be retrified

Retrificator root is a directory (placed anywhere) with the following files:
- `retrificator-log.txt` ordinary log file, created automatically
- `retrificator-state.json` file with current retrification state, created automatically
- `ignore-apps.txt` file containing java regexps (one regexp per line) for the web application names to be ignored (never retrified) by the retrificator. One regexp per line. Empty lines and comments (lines beginning with `#`) are ignored.

### bin
Binary (pre-built) releases
