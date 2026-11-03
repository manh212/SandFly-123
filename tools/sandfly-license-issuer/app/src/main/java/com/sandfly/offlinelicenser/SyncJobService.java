package com.sandfly.offlinelicenser;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SyncJobService extends JobService {
  private volatile int generation;
  @Override public boolean onStartJob(JobParameters parameters) {
    int current = ++generation;
    SyncCoordinator.WORKER.execute(() -> {
      boolean retry = SyncCoordinator.sync(getApplicationContext(), () -> generation != current);
      if (generation == current) jobFinished(parameters, retry);
    });
    return true;
  }
  @Override public boolean onStopJob(JobParameters parameters) {
    generation++;
    return true;
  }
}
