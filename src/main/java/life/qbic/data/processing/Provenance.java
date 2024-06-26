package life.qbic.data.processing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * <b>Provenance Information of Datasets</b>
 * <p>
 * Captures some dataset provenance metadata for pre-processing purposes.
 *
 * @since 1.0.0
 */
@JsonIgnoreProperties
public class Provenance {

  public static final String FILE_NAME = "provenance.json";

  /**
   * The path from where the dataset has been picked up originally.
   */
  @JsonProperty("origin")
  public String originPath;

  /**
   *
   */
  @JsonProperty("user")
  public String userWorkDirectoryPath;

  @JsonProperty("measurementId")
  public String qbicMeasurementID;

  @JsonProperty("datasetFiles")
  public List<String> datasetFiles;

  @JsonProperty("taskId")
  public String taskId;

  /**
   * A list of ordered processing folder stops the dataset has traversed and passed successfully.
   * <p>
   * The time of processing is ordered from oldest to latest.
   */
  @JsonProperty("history")
  public List<String> history;

  public static Provenance parse(Path json) throws ProvenanceException {
    File provenanceFile = json.toFile();
    if (!provenanceFile.exists()) {
      throw new ProvenanceException("File does not exist: %s".formatted(provenanceFile),
          ERROR_CODE.NOT_FOUND);
    }
    if (!provenanceFile.canRead()) {
      throw new ProvenanceException("Cannot read file: %s".formatted(provenanceFile),
          ERROR_CODE.PERMISSION_DENIED);
    }
    ObjectMapper mapper = new ObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    Provenance provenance;
    try {
      provenance = mapper.readValue(Files.readString(json), Provenance.class);
    } catch (JsonProcessingException e) {
      throw new ProvenanceException("Cannot read content %s".formatted(json), e,
          ERROR_CODE.UNKNOWN_CONTENT);
    } catch (IOException e) {
      throw new ProvenanceException("IO Error: %s".formatted(e.getMessage()), e,
          ERROR_CODE.IO_ERROR);
    }
    return provenance;
  }

  public static Optional<File> findProvenance(Path directory) {
    return Arrays.stream(Objects.requireNonNull(directory.toFile().listFiles()))
        .filter(file -> file.getName().equals(Provenance.FILE_NAME)).findFirst();
  }

  public void addToHistory(String event) {
    if (history == null) {
      history = new ArrayList<>();
    }
    history.add(event);
  }

  public enum ERROR_CODE {
    PERMISSION_DENIED,
    UNKNOWN_CONTENT,
    NOT_FOUND,
    IO_ERROR
  }

  public void addDatasetFiles(Collection<String> datasetFiles) {
    Objects.requireNonNull(datasetFiles);
    if (this.datasetFiles == null) {
      this.datasetFiles = new ArrayList<>();
    }
    this.datasetFiles.addAll(datasetFiles);
  }

  public void addDatasetFile(String datasetFile) {
    addDatasetFiles(Collections.singletonList(datasetFile));
  }

  public Collection<String> datasetFiles() {
    return datasetFiles.stream().toList();
  }

  public static class ProvenanceException extends RuntimeException {

    private final ERROR_CODE code;

    public ProvenanceException(String message, Throwable t, ERROR_CODE code) {
      super(message, t);
      this.code = code;
    }

    public ProvenanceException(String message, ERROR_CODE code) {
      super(message);
      this.code = code;
    }

    public ERROR_CODE code() {
      return code;
    }

  }
}
