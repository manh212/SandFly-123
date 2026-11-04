package com.google.android.accessibility.talkback.licensing;

import android.app.job.JobParameters;
import android.app.job.JobService;

public final class SupportJobService extends JobService {
  private volatile int generation;
  @Override public boolean onStartJob(JobParameters parameters) {
    int current = ++generation;
    SupportClient.WORKER.execute(() -> {
      boolean retry = SupportClient.drain(getApplicationContext(), () -> generation != current);
      if (generation == current) jobFinished(parameters, retry);
    });
    return true;
  }
  @Override public boolean onStopJob(JobParameters parameters) { generation++; return true; }
}
