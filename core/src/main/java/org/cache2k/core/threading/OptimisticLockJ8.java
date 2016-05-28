package org.cache2k.core.threading;

/*
 * #%L
 * cache2k core
 * %%
 * Copyright (C) 2000 - 2016 headissue GmbH, Munich
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

import java.util.concurrent.locks.StampedLock;

/**
 * @author Jens Wilke
 */
@SuppressWarnings("Since15")
public class OptimisticLockJ8 implements OptimisticLock {

  private StampedLock lock = new StampedLock();

  @Override
  public long readLock() {
    return lock.readLock();
  }

  @Override
  public long writeLock() {
    return lock.writeLock();
  }

  @Override
  public long tryOptimisticRead() {
    return lock.tryOptimisticRead();
  }

  @Override
  public boolean validate(final long stamp) {
    return lock.validate(stamp);
  }

  @Override
  public void unlockRead(final long stamp) {
    lock.unlockRead(stamp);
  }

  @Override
  public void unlockWrite(final long stamp) {
    lock.unlockWrite(stamp);
  }

  @Override
  public boolean canCheckHolder() {
    return false;
  }

  @Override
  public boolean isHoldingWriteLock() {
    return false;
  }
}
