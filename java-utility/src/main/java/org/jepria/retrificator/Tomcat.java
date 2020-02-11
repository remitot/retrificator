package org.jepria.retrificator;

import java.io.File;
import java.util.Collection;

public interface Tomcat {
  Collection<Webapp> getWebapps();
  Collection<File> getAccessLogs();
}
