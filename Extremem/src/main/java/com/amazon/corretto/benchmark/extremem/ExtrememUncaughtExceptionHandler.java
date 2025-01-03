// Copyright Amazon.com, Inc. or its affiliates.  All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazon.corretto.benchmark.extremem;

import java.lang.Thread;
import java.lang.OutOfMemoryError;

class ExtrememUncaughtExceptionHandler implements
                                       Thread.UncaughtExceptionHandler {
  static ExtrememUncaughtExceptionHandler instance = (
    new ExtrememUncaughtExceptionHandler());

  public void uncaughtException(Thread t, Throwable e) {
    try {
      Util.internalError("Thread " + t + ": terminated with uncaught exception: " + e);
    } finally {
      // Whenever any thread that is part of the Extremem workload is terminated, we should cancel
      // the simulation.  Otherwise, Extremem will report percentile latencies and the GC log will
      // report GC behaviors that do not truthfully represent the workload that was configured because
      // the workload has been reduced by the termination of certain worker threads.
      Util.severeInternalError();
    }
  }
}
