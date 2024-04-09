package life.qbic.data.processing;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b><class short description - 1 Line!></b>
 *
 * <p><More detailed description - When to use, what it solves, etc.></p>
 *
 * @since <version tag>
 */
public class GlobalConfig {

  private final Path usersErrorDirectoryName;

  public GlobalConfig(String usersErrorDirectoryName) {
    if (usersErrorDirectoryName == null || usersErrorDirectoryName.isBlank()) {
      throw new IllegalArgumentException("usersErrorDirectoryName cannot be null or empty");
    }
    this.usersErrorDirectoryName = Paths.get(usersErrorDirectoryName);
  }

  public Path usersErrorDirectory() {
    return this.usersErrorDirectoryName;
  }

}