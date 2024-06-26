package life.qbic.data.processing.evaluation;

import static org.apache.logging.log4j.LogManager.getLogger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import life.qbic.data.processing.ErrorSummary;
import life.qbic.data.processing.Provenance;
import life.qbic.data.processing.Provenance.ProvenanceException;
import life.qbic.data.processing.config.RoundRobinDraw;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.Logger;

/**
 * <b>Evaluation Request - Last process</b>
 *
 * <p>Validates the presence of a QBiC measurement ID in the dataset root
 * folder.</p> If a valid measurement ID is found, the process updates the provenance file with the
 * ID and moves the dataset to the openBIS ETL. After successful transfer, an openBIS marker-file is
 * created, to integrate the dataset registration with openBIS ETL.
 * <p>
 * If none is present, or the identifier does not match the requirements, it is moved back to the
 * users error folder.
 *
 * @since 1.0.0
 */
public class EvaluationRequest extends Thread {

  private static final String THREAD_NAME = "Evaluation-%s";
  private static final String INTERVENTION_DIRECTORY = "interventions";
  private static final Logger LOG = getLogger(EvaluationRequest.class);
  private static final Set<String> ACTIVE_TASKS = new HashSet<>();
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static int threadNumber = 1;
  private final Path interventionDirectory;
  private final AtomicBoolean active = new AtomicBoolean(false);
  private final AtomicBoolean terminated = new AtomicBoolean(false);
  private final Path workingDirectory;
  private final Path usersErrorDirectory;
  private final RoundRobinDraw<Path> targetDirectories;
  private Path assignedTargetDirectory;

  public EvaluationRequest(Path workingDirectory, RoundRobinDraw<Path> targetDirectories,
      Path usersErrorDirectory) {
    this.setName(THREAD_NAME.formatted(nextThreadNumber()));
    this.workingDirectory = workingDirectory;
    this.targetDirectories = targetDirectories;
    if (!workingDirectory.resolve(INTERVENTION_DIRECTORY).toFile().mkdir()
        && !workingDirectory.resolve(
        INTERVENTION_DIRECTORY).toFile().exists()) {
      throw new RuntimeException(
          "Could not create intervention directory for processing request at " + workingDirectory);
    }
    this.usersErrorDirectory = usersErrorDirectory;
    this.interventionDirectory = workingDirectory.resolve(INTERVENTION_DIRECTORY);
  }

  public EvaluationRequest(EvaluationConfiguration evaluationConfiguration) {
    this(evaluationConfiguration.workingDirectory(), evaluationConfiguration.targetDirectories(),
        evaluationConfiguration.usersErrorDirectory());
  }

  private static int nextThreadNumber() {
    return threadNumber++;
  }

  private static boolean push(String taskId) {
    LOCK.lock();
    boolean notActiveYet;
    try {
      notActiveYet = ACTIVE_TASKS.add(taskId);
    } finally {
      LOCK.unlock();
    }
    return notActiveYet;
  }

  @Override
  public void run() {
    while (true) {
      active.set(true);
      for (File taskDir : tasks()) {
        if (push(taskDir.getAbsolutePath()) && taskDir.exists()) {
          assignedTargetDirectory = getAssignedTargetDir();
          evaluateDirectory(taskDir);
          removeTask(taskDir);
        }
      }
      active.set(false);
      if (terminated.get()) {
        LOG.warn("Thread {} terminated", Thread.currentThread().getName());
        break;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        // We don't want to interrupt the thread here, only explicit to enable graceful shutdown
        // via its interrupt() method
      }
    }
  }

  private Path getAssignedTargetDir() {
    return targetDirectories.next();
  }

  private void removeTask(File taskDir) {
    LOCK.lock();
    try {
      ACTIVE_TASKS.remove(taskDir.getAbsolutePath());
    } finally {
      LOCK.unlock();
    }
  }

  private void evaluateDirectory(File taskDir) {
    var provenanceSearch = Provenance.findProvenance(taskDir.toPath());
    if (provenanceSearch.isEmpty()) {
      LOG.error("No provenance file found: {}", taskDir.getAbsolutePath());
      moveToSystemIntervention(taskDir, "Provenance file was not found");
      return;
    }

    Provenance provenance = null;
    try {
      provenance = Provenance.parse(provenanceSearch.get().toPath());
    } catch (ProvenanceException e) {
      LOG.error("Could not parse provenance file: {}}", taskDir.getAbsolutePath(), e);
      moveToSystemIntervention(taskDir, e.getMessage());
      return;
    }

    var measurementIdResult =
        provenance.qbicMeasurementID == null || provenance.qbicMeasurementID.isBlank()
            ? Optional.empty() : Optional.of(provenance.qbicMeasurementID);
    if (measurementIdResult.isPresent()) {
      provenance.addToHistory(taskDir.getAbsolutePath());
      try {
        updateProvenanceFile(provenanceSearch.get(), provenance);
      } catch (IOException e) {
        LOG.error("Could not update provenance file: {}", taskDir.getAbsolutePath(), e);
        moveToSystemIntervention(taskDir, e.getMessage());
      }
      try {
        copyToTargetDir(taskDir);
      } catch (IOException e) {
        LOG.error("Could not copy to target directory: {}", taskDir.getAbsolutePath(), e);
        moveToSystemIntervention(taskDir,
            "Cannot copy task to target directory: %s".formatted(assignedTargetDirectory));
      }
      try {
        createMarkerFile(assignedTargetDirectory, taskDir.getName());
      } catch (IOException e) {
        LOG.error("Could not create marker file in: {}", assignedTargetDirectory, e);
        moveToSystemIntervention(taskDir, e.getMessage());
      }
      try {
        cleanup(taskDir);
      } catch (IOException e) {
        LOG.error("Could not clean up task directory: {}", taskDir.getAbsolutePath(), e);
        moveToSystemIntervention(taskDir, e.getMessage());
      }
      return;
    }
    var errorMessage = ErrorSummary.createSimple(taskDir.getName(),
        String.join(", ", provenance.datasetFiles),
        "Missing QBiC measurement ID",
        "For a successful registration please provide the pre-registered QBiC measurement ID");
    LOG.error(
        "Missing measurement identifier: no known measurement id was found in the content of directory '{}' in task '{}'",
        String.join(", ", provenance.datasetFiles), taskDir.getName());
    moveBackToOrigin(taskDir, provenance, errorMessage.toString());
  }

  private void cleanup(File taskDir) throws IOException {
    LOG.info("Deleting task directory: {}", taskDir.getAbsolutePath());
    FileUtils.deleteDirectory(taskDir);
  }

  private void updateProvenanceFile(File provenanceFile, Provenance provenance) throws IOException {
    var mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter().writeValue(provenanceFile, provenance);
  }

  private boolean createMarkerFile(Path targetDirectory, String name) throws IOException {
    Path markerFileName = Paths.get(".MARKER_is_finished_" + name);
    return targetDirectory.resolve(markerFileName).toFile().createNewFile();
  }

  private void moveToSystemIntervention(File taskDir, String reason) {
    try {
      var errorFile = taskDir.toPath().resolve("error.txt").toFile();
      errorFile.createNewFile();
      Files.writeString(errorFile.toPath(), reason);
      Files.move(taskDir.toPath(), interventionDirectory.resolve(taskDir.getName()));
    } catch (IOException e) {
      throw new RuntimeException("Cannot move task to intervention: %s".formatted(taskDir), e);
    }
  }

  private void moveBackToOrigin(File taskDir, Provenance provenance, String reason) {
    LOG.info("Moving back to original user directory: {}",
        Paths.get(provenance.userWorkDirectoryPath).resolve(usersErrorDirectory));
    try {
      var errorFile = taskDir.toPath().resolve("error.txt").toFile();
      errorFile.createNewFile();
      Files.writeString(errorFile.toPath(), reason);
      Paths.get(provenance.userWorkDirectoryPath).resolve(usersErrorDirectory).toFile().mkdir();
      Files.move(taskDir.toPath(),
          Paths.get(provenance.userWorkDirectoryPath).resolve(usersErrorDirectory)
              .resolve(taskDir.getName()));
    } catch (IOException e) {
      LOG.error("Cannot move task to user intervention: %s".formatted(
          Paths.get(provenance.userWorkDirectoryPath).resolve(usersErrorDirectory)), e);
      moveToSystemIntervention(taskDir, e.getMessage());
    }
  }

  private void copyToTargetDir(File taskDir) throws IOException {
    LOG.info(
        "Copying %s to target directory %s".formatted(taskDir.getAbsolutePath(),
            assignedTargetDirectory));
    FileUtils.copyDirectory(taskDir, assignedTargetDirectory.resolve(taskDir.getName()).toFile());
  }

  private List<File> tasks() {
    return Arrays.stream(workingDirectory.toFile().listFiles()).filter(File::isDirectory)
        .filter(file -> !file.getName().equals(INTERVENTION_DIRECTORY)).toList();
  }

  public void interrupt() {
    terminated.set(true);
    while (active.get()) {
      LOG.debug("Thread is still active...");
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        // we don't want to interrupt the worker thread before its task is done, since it might
        // render the application in a non-recoverable state
      }
    }
    LOG.debug("Task has been finished");
  }

}
