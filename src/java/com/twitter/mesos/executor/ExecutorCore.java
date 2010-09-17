package com.twitter.mesos.executor;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.twitter.common.BuildInfo;
import com.twitter.common.base.Closure;
import com.twitter.common.base.ExceptionalClosure;
import com.twitter.common.base.ExceptionalFunction;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Data;
import com.twitter.common.quantity.Time;
import com.twitter.mesos.FrameworkMessageCodec;
import com.twitter.mesos.StateTranslator;
import com.twitter.mesos.codec.Codec;
import com.twitter.mesos.executor.HealthChecker.HealthCheckException;
import com.twitter.mesos.executor.ProcessKiller.KillCommand;
import com.twitter.mesos.executor.ProcessKiller.KillException;
import com.twitter.mesos.gen.ExecutorStatus;
import com.twitter.mesos.gen.LiveTaskInfo;
import com.twitter.mesos.gen.RegisteredTaskUpdate;
import com.twitter.mesos.gen.ScheduleStatus;
import com.twitter.mesos.gen.SchedulerMessage;
import com.twitter.mesos.gen.TwitterTaskInfo;
import mesos.ExecutorDriver;
import mesos.TaskDescription;
import mesos.TaskState;
import mesos.TaskStatus;
import org.apache.commons.io.FileSystemUtils;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ExecutorCore
 *
 * @author wfarner
 */
public class ExecutorCore implements TaskManager {
  private static final Logger LOG = Logger.getLogger(MesosExecutorImpl.class.getName());

  /**
   * {@literal @Named} binding key for the executor root directory.
   */
  static final String EXECUTOR_ROOT_DIR =
      "com.twitter.mesos.executor.ExecutorCore.EXECUTOR_ROOT_DIR";

  private final Map<Integer, RunningTask> tasks = Maps.newConcurrentMap();

  private static final byte[] EMPTY_MSG = new byte[0];

  private final File executorRootDir;

  private final ResourceManager resourceManager;

  private final AtomicReference<ExecutorDriver> driver = new AtomicReference<ExecutorDriver>();

  private final ExecutorService executorService = Executors.newCachedThreadPool(
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("MesosExecutor-[%d]").build());

  private final ScheduledExecutorService syncExecutor = new ScheduledThreadPoolExecutor(1,
        new ThreadFactoryBuilder().setDaemon(true).setNameFormat("ExecutorSync-%d").build());

  @Inject ExceptionalFunction<FileCopyRequest, File, IOException> fileCopier;
  @Inject SocketManager socketManager;
  @Inject ExceptionalFunction<Integer, Boolean, HealthCheckException> healthChecker;
  @Inject ExceptionalClosure<KillCommand, KillException> processKiller;
  @Inject ExceptionalFunction<File, Integer, FileToInt.FetchException> pidFetcher;

  private final AtomicReference<String> slaveId = new AtomicReference<String>();
  private final BuildInfo buildInfo;

  // TODO(wfarner): Remove TwitterExecutorOptions from args, make a named File.
  @Inject
  private ExecutorCore(@Named(EXECUTOR_ROOT_DIR) File taskRootDir, BuildInfo buildInfo) {
    executorRootDir = Preconditions.checkNotNull(taskRootDir);
    if (!executorRootDir.exists()) {
      Preconditions.checkState(executorRootDir.mkdirs());
    }

    this.buildInfo = Preconditions.checkNotNull(buildInfo);

    resourceManager = new ResourceManager(this, executorRootDir);
    resourceManager.start();

    startStateSync();
    startRegisteredTaskPusher();
  }

  void setSlaveId(String slaveId) {
    this.slaveId.set(Preconditions.checkNotNull(slaveId));
  }

  // TODO(flo): Handle loss of connection with the ExecutorDriver.
  // TODO(flo): Do input validation on parameters.
  public void executePendingTask(final ExecutorDriver driver, final TwitterTaskInfo taskInfo,
                                 final int taskId) {
    this.driver.set(driver);

    LOG.info(String.format("Received task for execution: %s/%s - %d", taskInfo.getOwner(),
        taskInfo.getJobName(), taskId));
    final RunningTask runningTask = new RunningTask(socketManager, healthChecker, processKiller,
        pidFetcher, executorRootDir, taskId, taskInfo, fileCopier);

    try {
      runningTask.stage();
      runningTask.launch();
      sendStatusUpdate(driver, new TaskStatus(taskId, TaskState.TASK_RUNNING, EMPTY_MSG));
    } catch (RunningTask.ProcessException e) {
      LOG.log(Level.SEVERE, "Failed to stage task " + taskId, e);
      sendStatusUpdate(driver, new TaskStatus(taskId, TaskState.TASK_FAILED, EMPTY_MSG));
      return;
    } catch (Throwable t) {
      LOG.log(Level.SEVERE, "Unhandled exception while launching task.", t);
      sendStatusUpdate(driver, new TaskStatus(taskId, TaskState.TASK_FAILED, EMPTY_MSG));
      return;
    }

    tasks.put(taskId, runningTask);

    executorService.execute(new Runnable() {
      @Override public void run() {
        LOG.info("Waiting for task " + taskId + " to complete.");
        ScheduleStatus state = runningTask.waitFor();
        LOG.info("Task " + taskId + " completed in state " + state);

        sendStatusUpdate(driver, new TaskStatus(taskId, StateTranslator.get(state), EMPTY_MSG));
      }
    });
  }

  public void stopRunningTask(int taskId) {
    RunningTask task = tasks.get(taskId);

    if (task != null) {
      LOG.info("Killing task: " + task);
      task.terminate(ScheduleStatus.KILLED);
    } else {
      LOG.severe("No such task found: " + taskId);
    }
  }

  public RunningTask getTask(int taskId) {
    return tasks.get(taskId);
  }

  public Iterable<RunningTask> getTasks() {
    return tasks.values();
  }

  @Override
  public Iterable<RunningTask> getRunningTasks() {
    return Iterables.unmodifiableIterable(Iterables.filter(tasks.values(),
        new Predicate<RunningTask>() {
          @Override public boolean apply(RunningTask task) {
            return task.isRunning();
          }
        }));
  }

  @Override
  public boolean hasTask(int taskId) {
    return tasks.containsKey(taskId);
  }

  @Override
  public boolean isRunning(int taskId) {
    return hasTask(taskId) && tasks.get(taskId).isRunning();
  }

  @Override
  public void deleteCompletedTask(int taskId) {
    Preconditions.checkArgument(!isRunning(taskId), "Task " + taskId + " is still running!");
    tasks.remove(taskId);
  }

  public void shutdownCore() {
    for (Map.Entry<Integer, RunningTask> entry : tasks.entrySet()) {
      System.out.println("Killing task " + entry.getKey());
      stopRunningTask(entry.getKey());
    }
  }

  @VisibleForTesting
  void sendStatusUpdate(ExecutorDriver driver, TaskStatus status) {
    Preconditions.checkNotNull(status);
    if (driver != null) {
      LOG.info("Notifying task " + status.getTaskId() + " in state " + status.getState());
      driver.sendStatusUpdate(status);
    } else {
      LOG.severe("No executor driver available, unable to send signals.");
    }
  }

  private static String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.log(Level.SEVERE, "Failed to look up own hostname.", e);
      return null;
    }
  }

  private void startStateSync() {
    final Properties buildProperties = buildInfo.getProperties();

    final String DEFAULT = "unknown";
    final ExecutorStatus baseStatus = new ExecutorStatus()
        .setHost(getHostName())
        .setBuildUser(buildProperties.getProperty(BuildInfo.Key.USER.value, DEFAULT))
        .setBuildMachine(buildProperties.getProperty(BuildInfo.Key.MACHINE.value, DEFAULT))
        .setBuildPath(buildProperties.getProperty(BuildInfo.Key.PATH.value, DEFAULT))
        .setBuildGitTag(buildProperties.getProperty(BuildInfo.Key.GIT_TAG.value, DEFAULT))
        .setBuildGitRevision(buildProperties.getProperty(BuildInfo.Key.GIT_REVISION.value, DEFAULT))
        .setBuildTimestamp(buildProperties.getProperty(BuildInfo.Key.TIMESTAMP.value, DEFAULT));

    Runnable syncer = new Runnable() {
      @Override public void run() {
        if (slaveId.get() == null) return;

        ExecutorStatus status = new ExecutorStatus(baseStatus)
            .setSlaveId(slaveId.get());

        try {
          status.setDiskFreeKb(FileSystemUtils.freeSpaceKb(executorRootDir.getAbsolutePath()));
        } catch (IOException e) {
          LOG.log(Level.INFO, "Failed to get disk free space.", e);
        }

        LOG.info("Sending executor status update: " + status);


          SchedulerMessage message = new SchedulerMessage();
          message.setExecutorStatus(status);
        try {
          sendSchedulerMessage(message);
        } catch (Codec.CodingException e) {
          LOG.log(Level.WARNING, "Failed to send executor status.", e);
        }
      }
    };

    // TODO(wfarner): Make sync interval configurable.
    syncExecutor.scheduleAtFixedRate(syncer, 30, 30, TimeUnit.SECONDS);
  }

  private void sendSchedulerMessage(SchedulerMessage message) throws Codec.CodingException {
    ExecutorDriver driverRef = driver.get();
    if (driverRef == null) {
      LOG.info("No driver available, unable to send executor status.");
      return;
    }

    int result = driverRef.sendFrameworkMessage(
        new FrameworkMessageCodec<SchedulerMessage>(SchedulerMessage.class).encode(message));
    if (result != 0) {
      LOG.warning("Scheduler message failed to send, return code " + result);
    }
  }

  private void startRegisteredTaskPusher() {
    Runnable pusher = new Runnable() {
      @Override public void run() {
        RegisteredTaskUpdate update = new RegisteredTaskUpdate()
            .setSlaveHost(getHostName());

        for (Map.Entry<Integer, RunningTask> task : tasks.entrySet()) {
          LiveTaskInfo info = new LiveTaskInfo();
          info.setTaskId(task.getKey());
          RunningTask runningTask = task.getValue();
          info.setTaskInfo(runningTask.getTask());
          info.setResources(runningTask.getResourceConsumption());
          info.setStatus(runningTask.getStatus());

          update.addToTaskInfos(info);
        }

        try {
          SchedulerMessage message = new SchedulerMessage();
          message.setTaskUpdate(update);

          sendSchedulerMessage(message);
        } catch (Codec.CodingException e) {
          LOG.log(Level.WARNING, "Failed to send executor status.", e);
        }
      }
    };

    // TODO(wfarner): Make push interval configurable.
    syncExecutor.scheduleAtFixedRate(pusher, 5, 5, TimeUnit.SECONDS);
  }
}
