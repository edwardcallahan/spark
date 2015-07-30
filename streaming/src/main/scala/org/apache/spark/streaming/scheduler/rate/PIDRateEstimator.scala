/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.streaming.scheduler.rate

/**
 * Implements a proportional-integral-derivative (PID) controller which acts on
 * the speed of ingestion of elements into Spark Streaming. A PID controller works
 * by calculating an '''error''' between a measured output and a desired value. In the
 * case of Spark Streaming the error is the difference between the measured processing
 * rate (number of elements/processing delay) and the previous rate.
 *
 * @see https://en.wikipedia.org/wiki/PID_controller
 *
 * @param batchDurationMillis the batch duration, in milliseconds
 * @param proportional how much the correction should depend on the current
 *        error. This term usually provides the bulk of correction. A value too large would
 *        make the controller overshoot the setpoint, while a small value would make the
 *        controller too insensitive. The default value is -1.
 * @param integral how much the correction should depend on the accumulation
 *        of past errors. This term accelerates the movement towards the setpoint, but a large
 *        value may lead to overshooting. The default value is -0.2.
 * @param derivative how much the correction should depend on a prediction
 *        of future errors, based on current rate of change. This term is not used very often,
 *        as it impacts stability of the system. The default value is 0.
 */
private[streaming] class PIDRateEstimator(
    batchIntervalMillis: Long,
    proportional: Double = -1D,
    integral: Double = -.2D,
    derivative: Double = 0D)
  extends RateEstimator {

  private var firstRun: Boolean = true
  private var latestTime: Long = -1L
  private var latestRate: Double = -1D
  private var latestError: Double = -1L

  require(
    batchIntervalMillis > 0,
    s"Specified batch interval $batchIntervalMillis in PIDRateEstimator is invalid.")

  def compute(time: Long, // in milliseconds
      elements: Long,
      processingDelay: Long, // in milliseconds
      schedulingDelay: Long // in milliseconds
    ): Option[Double] = {

    this.synchronized {
      if (time > latestTime && processingDelay > 0 && batchIntervalMillis > 0) {

        // in seconds, should be close to batchDuration
        val delaySinceUpdate = (time - latestTime).toDouble / 1000

        // in elements/second
        val processingRate = elements.toDouble / processingDelay * 1000

        // in elements/second
        val error = latestRate - processingRate

        // The error integral, based on schedulingDelay as an indicator for accumulated errors
        // a scheduling delay s corresponds to s * processingRate overflowing elements. Those
        // are elements that couldn't be processed in previous batches, leading to this delay.
        // We assume the processingRate didn't change too much.
        // from the number of overflowing elements we can calculate the rate at which they would be
        // processed by dividing it by the batch interval. This rate is our "historical" error,
        // or integral part, since if we subtracted this rate from the previous "calculated rate",
        // there wouldn't have been any overflowing elements, and the scheduling delay would have
        // been zero.
        // (in elements/second)
        val historicalError = schedulingDelay.toDouble * processingRate / batchIntervalMillis

        // in elements/(second ^ 2)
        val dError = (error - latestError) / delaySinceUpdate

        val newRate = (latestRate + proportional * error +
                                    integral * historicalError +
                                    derivative * dError).max(0.0)
        latestTime = time
        if (firstRun) {
          latestRate = processingRate
          latestError = 0D
          firstRun = false

          None
        } else {
          latestRate = newRate
          latestError = error

          Some(newRate)
        }
      } else None
    }
  }
}
