package org.jepria.retrificator;

import java.io.File;
import java.util.Objects;

public class Webapp {
  /**
   * NotNull
   */
  public final String name;
  /**
   * Nullable
   */
  public final File war;
  /**
   * Nullable
   */
  public final File warRetro;
  /**
   * Nullable
   * directory of the deployed webapp
   */
  public final File deployed;
  
  /**
   *
   * @param name NotNulll
   * @param war
   * @param warRetro
   * @param deployed
   */
  public Webapp(String name, File war, File warRetro, File deployed) {
    Objects.requireNonNull(name);
    this.name = name;
    this.war = war;
    this.warRetro = warRetro;
    this.deployed = deployed;
  }
}
