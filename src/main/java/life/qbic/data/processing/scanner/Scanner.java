package life.qbic.data.processing.scanner;

import static org.apache.logging.log4j.LogManager.getLogger;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import life.qbic.data.processing.ConcurrentRegistrationQueue;
import life.qbic.data.processing.registration.RegistrationRequest;
import org.apache.logging.log4j.Logger;

/**
 * <b><class short description - 1 Line!></b>
 *
 * <p><More detailed description - When to use, what it solves, etc.></p>
 *
 * @since <version tag>
 */
public class Scanner extends Thread {

  private static final Logger log = getLogger(Scanner.class);
  private static final Path REGISTRATION_PATH = Paths.get("registration");

  private final Path scannerPath;
  private final int scanInterval;
  private final HashSet<Path> userProcessDirectories = new HashSet<>();
  private final ConcurrentRegistrationQueue registrationQueue;
  private final HashSet<RegistrationRequest> submittedRequests = new HashSet<>();

  public Scanner(ScannerConfiguration scannerConfiguration,
      ConcurrentRegistrationQueue registrationQueue) {
    this.setName("Scanner-Thread");
    Objects.requireNonNull(scannerConfiguration, "scannerConfiguration must not be null");
    scannerPath = Path.of(scannerConfiguration.scannerDirectory());
    if (!scannerPath.toFile().exists()) {
      throw new RuntimeException("Could not find scanner directory: " + scannerPath);
    }
    this.scanInterval = scannerConfiguration.scanInterval();
    this.registrationQueue = Objects.requireNonNull(registrationQueue,
        "eventQueue must not be null");
  }

  @Override
  public void run() {
    log.info("Started scanning '%s'".formatted(scannerPath));
    while (!Thread.interrupted()) {
      try {
        var userFolderIterator = Arrays.stream(
                Objects.requireNonNull(scannerPath.toFile().listFiles())).filter(File::isDirectory)
            .toList().iterator();

        while (userFolderIterator.hasNext()) {
          fetchRegistrationDirectory(userFolderIterator.next().toPath()).ifPresent(
              this::addRegistrationDirectory);
        }

        List<RegistrationRequest> requests = detectDataForRegistration();
        for (RegistrationRequest request : requests) {
          if (submittedRequests.contains(request)) {
            log.debug("Skipping registration request '{}'", request);
            continue;
          }
          registrationQueue.add(request);
          submittedRequests.add(request);
          log.info("New registration requested: %s".formatted(request));
        }
        removePathZombies();
        Thread.sleep(scanInterval);
      } catch (InterruptedException e) {
        interrupt();
      }
    }
    log.info("Stopped scanning '%s'".formatted(scannerPath));
  }

  private List<RegistrationRequest> detectDataForRegistration() {
    return userProcessDirectories.parallelStream()
        .map(Path::toFile)
        .map(File::listFiles)
        .map(this::createRequests).flatMap(
            Collection::stream).toList();
  }

  private List<RegistrationRequest> createRequests(File[] files) {
    if (files == null || files.length == 0) {
      return new ArrayList<>();
    }
    return Arrays.stream(files).filter(file -> !file.isHidden()).map(this::createRequest).toList();
  }

  private RegistrationRequest createRequest(File file) {
    return new RegistrationRequest(Instant.now(), file.lastModified(),
        file.getParentFile().toPath(), file.toPath());
  }

  private void removePathZombies() {
    List<Path> zombies = new LinkedList<>();
    for (Path processFolder : userProcessDirectories) {
      if (!processFolder.toFile().exists()) {
        zombies.add(processFolder);
      }
    }

    zombies.forEach(zombie -> {
      userProcessDirectories.remove(zombie);
      log.warn("Removing orphaned process directory: '%s'".formatted(zombie));
    });

  }

  private void addRegistrationDirectory(Path path) {
    if (userProcessDirectories.add(path)) {
      log.info("New user process directory found: '%s'".formatted(path.toString()));
    }
  }

  public Optional<Path> fetchRegistrationDirectory(Path userDirectory) {
    Path resolvedPath = userDirectory.resolve(REGISTRATION_PATH);
    return Optional.ofNullable(resolvedPath.toFile().exists() ? resolvedPath : null);
  }

  @Override
  public void interrupt() {
    log.info("Interrupted scanning '%s'".formatted(scannerPath));
  }
}
