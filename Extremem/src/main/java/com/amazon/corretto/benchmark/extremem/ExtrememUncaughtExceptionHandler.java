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
      if (e.getClass() == Class.forName("java.lang.OutOfMemoryError")) {
        Util.severeInternalError();
      }
    } catch (Throwable x) {
      Util.severeInternalError();
    }
  }
}
