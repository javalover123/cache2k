package org.cache2k.test.stress;

/*
 * #%L
 * cache2k implementation
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

import org.cache2k.Cache2kBuilder;
import org.cache2k.test.core.StaticUtil;
import static org.junit.Assert.*;

import org.cache2k.testing.category.SlowTests;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.runners.Parameterized.Parameters;

/**
 * Concurrent test suite for (some) atomic operations
 *
 * @author Jens Wilke
 */
@RunWith(Parameterized.class)
@Category(SlowTests.class)
public class AtomicOperationsPairwiseStressTest extends PairwiseTestingBase {

  public AtomicOperationsPairwiseStressTest(Object obj) {
    super(obj);
  }

  @Parameters
  public static Iterable<Object[]> data() {
    List l = new ArrayList();
    l.add(new Object[]{new BuilderAugmenter() {
      @Override
      public <K, V> Cache2kBuilder<K, V> augment(Cache2kBuilder<K, V> b) { return b; }
    } });
    l.add(new Object[]{new BuilderAugmenter() {
      @Override
      public <K, V> Cache2kBuilder<K, V> augment(Cache2kBuilder<K, V> b) {
        StaticUtil.enforceWiredCache(b); return b;
      }
    } });
    return l;
  }

  static class ReplaceIfEquals1 extends CacheKeyActorPair<Boolean, Integer, Integer> {
    public void setup() {
      cache.put(key, 10);
    }
    public Boolean actor1() {
      return cache.replaceIfEquals(key, 10, 20);
    }
    public Boolean actor2() {
      return cache.replaceIfEquals(key, 10, 30);
    }
    public void check(Boolean r1, Boolean r2) {
      SuccessTuple r = new SuccessTuple(r1, r2);
      assertTrue("one actor wins", r.isOneSucceeds());
      assertEquals("cached value", r.isSuccess1() ? 20 : 30, (int) value());
    }
  }

  static class ReplaceIfEquals2 extends CacheKeyActorPair<Boolean, Integer, Integer> {
    public void setup() {
      cache.put(key, 10);
    }
    public Boolean actor1() {
      return cache.replaceIfEquals(key, 10, 20);
    }
    public Boolean actor2() {
      return cache.replaceIfEquals(key, 20, 30);
    }
    public void check(Boolean r1, Boolean r2) {
      SuccessTuple r = new SuccessTuple(r1, r2);
      assertTrue(!r.isBothSucceed() || value() == 30);
      assertTrue(!r.isOneSucceeds() || (value() == 20 && r1));
    }
  }

  static class PutIfAbsent1 extends CacheKeyActorPair<Boolean, Integer, Integer> {
    public void setup() {
      cache.remove(key);
    }
    public Boolean actor1() {
      return cache.putIfAbsent(key, 10);
    }
    public Boolean actor2() {
      return cache.putIfAbsent(key, 20);
    }
    public void check(Boolean r1, Boolean r2) {
      assertTrue(new SuccessTuple(r1, r2).isOneSucceeds());
      assertTrue(!r1 || value() == 10);
      assertTrue(!r2 || value() == 20);
    }
  }

  static class PeekAndPut1 extends CacheKeyActorPair<Integer, Integer, Integer> {
    public void setup() { cache.remove(key); }
    public Integer actor1() { return cache.peekAndPut(key, 20); }
    public Integer actor2() { return cache.peekAndPut(key, 30); }
    public void check(Integer r1, Integer r2) {
      Integer value = value();
      assertTrue("one actor sees no mapping", r1 == null || r2 == null);
      assertTrue(!(r1 == null) || (value == 30 && r2 == 20));
      assertTrue(!(r2 == null) || (value == 20 && r1 == 30));
    }
  }

  static class PeekAndReplace1 extends CacheKeyActorPair<Integer, Integer, Integer> {
    public void setup() {
      cache.put(key, 10);
    }
    public Integer actor1() {
      return cache.peekAndReplace(key, 20);
    }
    public Integer actor2() {
      return cache.peekAndReplace(key, 30);
    }
    public void check(Integer r1, Integer r2) {
      assertTrue(!(r1 == 30) || (value() == 20 && r2 == 10));
      assertTrue(!(r1 == 10) || (value() == 30 && r2 == 20));
      assertTrue(!(r2 == 20) || (value() == 30 && r1 == 10));
      assertTrue(!(r2 == 10) || (value() == 20 && r1 == 30));
    }
  }

  static class ComputeIfAbsent1 extends CacheKeyActorPair<Integer, Integer, Integer> {
    final AtomicInteger count = new AtomicInteger(0);
    public void setup() {
      count.set(0);
      cache.remove(key);
    }
    public Integer actor1() {
      return cache.computeIfAbsent(key, new Callable<Integer>() {
        @Override
        public Integer call() {
          count.getAndIncrement();
          return 1;
        }
      });
    }
    public Integer actor2() {
      return cache.computeIfAbsent(key, new Callable<Integer>() {
        @Override
        public Integer call() {
          count.getAndIncrement();
          return 2;
        }
      });
    }
    public void check(Integer r1, Integer r2) {
      assertEquals("compute called once", 1, count.get());
      assertEquals("actor sees computed value of the other", r1, r2);
      assertEquals(r1, value());
    }
  }

}