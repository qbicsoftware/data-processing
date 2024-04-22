# Data processing

A small Java application that listens to data ready for registration and performs several
pre-registration
checks before moving the dataset to an openBIS ETL routine.

## Run the app

Checkout the latest code from `main` and run the Maven goal `spring-boot:run`:

```
mvn spring-boot:run
```

You have to set different environment variables first to configure the individual process parts.
Have a look at the [configuration](#configuration) setting to learn more .

## What the app does

The following figure gives an overview of the process building blocks and flow:

<img src="./img/process-flow.jpg">

The **basic process flow** can be best described with:

1. Scanning step
2. Registration step (preparation)
3. 1 to N processing steps

The last processing step usually hands the dataset over to the actual registration system, in our
case it is several
openBIS ETL dropboxes. In the current implementation, a marker file is created after successful
transfer into the target folder: `.MARKER_is_finished_[dataset name]`

The current implementation consists of 4 steps: _scanning, registration, processing, evaluation_ and
are described in the following subsections.

### Scanning

In this step, the application scans a [pre-defined path](#scanner-step-config) and looks for
existing registration folders.
If a registration folder is present, it is recorded and will be investigated. All other files in a
user's directory will be ignored.

> [!NOTE]
> It is important that the move operation of any dataset in the registration folder is **atomic**!
> Otherwise, data corruption will occur. Ideally the dataset is staged into the user's home folder
> first (e.g. a copy operation, an upload via SFTP or SSH) and then **moved** into the registration
> folder.
>
> Moving operations on the same file system are basically a rename of the file path and
> atomic.

Once a new dataset is detected, it gets queued for registration and the next step will take over.

A registration request gets only submitted once to the registration queue and will subsequently get
ignored by the scanning process, as long as the filename or modification timestamp does not change.

If the application quits or stops unexpectedly, on re-start they will get detected and resubmitted
again.

### Registration

This process step is preparing the dataset registration for subsequent pre-registration task, to
guarantee a unified structure and processing model, other steps can build on and take actions
accordingly (e.g.
harmonised error handling).

Its configuration parameters can be set via environment variables, see
the [registration step config](#registration-step-config) section to learn more.

In the current implementation, the registration step does two things:

1. Assign every task a unique ID
2. Provide provenance information

The task id is just a randomly generated UUID-4 to ensure that datasets with the same name do not
get
overwritten during the processing.

The provenance information will be written into the task directory in an own file next to the
dataset
and is of type JSON.

The final task directory structure looks then like this (task dir name is an example):

```bash provenance.json
 |- 74c5d26f-b756-42c3-b6f4-2b4825670a2d
        |- my_dataset
        |- provenance.json
```

Here is an example of the provenance file:

```json
{
  "origin": "/Users/myuser/Downloads/scanner-test/user1/registration",
  "user": "/Users/myuser/Downloads/scanner-test/user1",
  "measurementId": "NGSQTEST001AE-23214214455",
  "history": [
    "/Users/myuser/Downloads/scanner-working-dir/74c5d26f-b756-42c3-b6f4-2b4825670a2d/proteomics_measurements(48).xlsx",
    "/Users/sven1103/Downloads/scanner-processing-dir/74c5d26f-b756-42c3-b6f4-2b4825670a2d"
  ]
}
```

> [!NOTE]
> The following properties can be expected after all process steps have been executed:
>
> `origin`: from which path the dataset has been detected during scanning
>
> `user`:  from which user directory the dataset has been picked up
>
> `measurementId`: any valid QBiC measurement ID that has been found in the dataset (this might
> be `null`) in case the evaluation has not been done yet.
>
> `history`: a list of history items, which steps have been performed. The list is ordered by first
> processing steps being at the start and the latest at the end.

### Processing

In the current implementation, we prepare the dataset package. Every dataset is a directory, we do
not rely
on what the user has provided.

In case the dataset is a single file, the application creates a wrapping directory with the same
name and the suffix `_dataset` (see example below).

> [!NOTE]
> Transforming every dataset as a folder makes it easier to process the dataset in (future)
> downstream processes (e.g. quality control, checksum validation, etc).
> We create a harmonised structure of the task directory content, that can be relied on:
>
> ```
> |- 74c5d26f-b756-42c3-b6f4-2b4825670a2d  // directory
>       |- a_file.txt_dataset  // dataset folder
>               |- a_file.txt  // the original file
>       |- provenance.json  // file
> ```

### Evaluation

Last but not least, this step looks for any present QBiC measurement ID in the dataset name. If none
is given, the registration cannot be executed.

In this case the process moves the task directory into the user's home error folder. After the user
has
provided a valid QBiC measurement id, they can move the dataset into registration again.

## Configuration

### Global settings

```properties
#------------------------
# Global settings
#------------------------
# Directory name that will be used for the manual intervention directory
# Created in the users' home folders
# e.g. /home/<user1>/error
users.error.directory.name=error
# Directory name that will be used for the detecting dropped datasets
# Needs to be present in the users' home folders
# e.g. /home/<user1>/registration
users.registration.directory.name=registration
```

Configure the names of the two application directories for error handling and registration.

> [!NOTE]
> The `registration` folder needs to be present, the application is not creating it automatically,
> no
> prevent accidental dataset overwrite.

### Scanner step config

```properties
#--------------------------------------
# Settings for the data scanning thread
#--------------------------------------
# Path to the directory that contains all user directories
# e.g. /home in Linux or /Users in macOS
scanner.directory=${SCANNER_DIR:/home}
# The time interval (milliseconds) the scanner thread iterates through the scanner directory
# Value must be an integer > 0
scanner.interval=1000
```

Sets the applications top level scanning directory and considers every folder in it as an own
user directory.

The scanner interval is set to 1 second by default is not yet supposed to be configured via
environment variables (if required, override it with command line arguments).

### Registration step config

Sets the number of threads per process, its working directory and the target directory, to where
finished tasks are moved to after successful operation.

```properties
#----------------
# Settings for the registration worker threads
#----------------
registration.threads=2
registration.working.dir=${WORKING_DIR:}
registration.target.dir=${PROCESSING_DIR:}
```

### Processing step config

Sets the number of threads per process, its working directory and the target directory, to where
finished tasks are moved to after successful operation. 

```properties
#------------------------------------
# Settings for the 1. processing step
# Proper packaging and provenance data, some simple checks
#------------------------------------
processing.threads=2
processing.working.dir=${PROCESSING_DIR}
processing.target.dir=${EVALUATION_DIR}
```

### Evaluation step config

Sets the number of threads per process, its working directory and the target directory, to where
finished tasks are moved to after successful operation.

```properties
#----------------------------------
# Setting for the 2. processing step:
# Measurement ID evaluation
# ---------------------------------
evaluations.threads=2
evaluation.working.dir=${EVALUATION_DIR}
evaluation.target.dir=${OPENBIS_ETL_DIR}
evaluation.measurement-id.pattern=^(MS|NGS)Q[A-Z0-9]{4}[0-9]{3}[A-Z0-9]{2}-[0-9]*
```


