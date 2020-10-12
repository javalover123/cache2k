package org.cache2k.integration;

/*
 * #%L
 * cache2k API
 * %%
 * Copyright (C) 2000 - 2020 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

/**
 * Relevant information of load attempt that generated an exception.
 * This is used by the exception propagator and the resilience policy.
 *
 * <p>Compatibility: This interface is not intended for implementation
 * or extension by a client and may get additional methods in a new minor release.
 *
 * <p>Rationale: The information does not contain the time of the original
 * expiry of the cache value. This is intentional. There is no information
 * field to record the time of expiry past the expiry itself. The expiry time
 * information is reset as soon as the expiry time is reached. To produce no
 * overhead, the information provided by this interface is captured only
 * in case an exception happens.
 *
 * @see ExceptionPropagator
 * @see ResiliencePolicy
 * @author Jens Wilke
 * @deprecated replaced with {@link org.cache2k.io.ExceptionInformation}
 */
@Deprecated
public interface ExceptionInformation {

  /**
   * The exception propagator in effect.
   *
   * @since 1.4
   */
  ExceptionPropagator getExceptionPropagator();

  /**
   * The original exception generated by the last recent loader call.
   */
  Throwable getException();

  /**
   * Number of retry attempts to load the value for the requested key.
   * The value is starting 0 for the first load attempt that yields an
   * exception. The counter is incremented for each consecutive
   * loader exception. After a successful attempt to load the value the
   * counter is reset.
   *
   * @return counter starting at 0 for the first load attempt that
   *         yields an exception.
   */
  int getRetryCount();

  /**
   * Start time of the load that generated the first exception.
   *
   * @return time in millis since epoch
   */
  long getSinceTime();

  /**
   * Start time of the load operation that generated the recent exception.
   *
   * @return time in millis since epoch
   */
  long getLoadTime();

  /**
   * Time in millis until the next retry attempt.
   * This property is only set in the context of the {@link ExceptionPropagator}.
   *
   * @return time in millis since epoch
   */
  long getUntil();

}
