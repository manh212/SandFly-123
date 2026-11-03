package com.sandfly.offlinelicenser;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SyncJobService extends JobService {
  private volatile boolean stopped;
  @Override public boolean onStartJob(JobParameters parameters) {
    stopped = false;
    SyncCoordinator.WORKER.execute(() -> {
      boolean retry = SyncCoordinator.sync(getApplicationContext(), () -> stopped);
      if (!stopped) jobFinished(parameters, retry);
    });
    return true;
  }
  @Override public boolean onStopJob(JobParameters parameters) {
    stopped = true;
    return true;
  }
}
