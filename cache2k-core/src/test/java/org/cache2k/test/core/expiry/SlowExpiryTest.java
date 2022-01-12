package org.cache2k.test.core.expiry;

/*-
 * #%L
 * cache2k core implementation
 * %%
 * Copyright (C) 2000 - 2022 headissue GmbH, Munich
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
import org.cache2k.core.timing.DefaultTimer;
import org.cache2k.core.timing.TimingUnitTest;
import org.cache2k.expiry.ExpiryTimeValues;
import org.cache2k.io.AsyncCacheLoader;
import org.cache2k.test.core.BasicCacheTest;
import org.cache2k.test.util.TestingBase;
import org.cache2k.test.util.IntCountingCacheSource;
import org.cache2k.CacheEntry;
import org.cache2k.io.LoadExceptionInfo;
import org.cache2k.io.ResiliencePolicy;
import org.cache2k.Cache;

import org.cache2k.io.CacheLoaderException;
import org.cache2k.test.core.TestingParameters;
import org.cache2k.core.api.InternalCacheInfo;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import org.cache2k.testing.category.SlowTests;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Expiry tests that work with some larger durations. Slow when tested in real time.
 *
 * @author Jens Wilke
 */
@SuppressWarnings("unchecked")
@Category(SlowTests.class)
public class SlowExpiryTest extends TestingBase {

  /**
   * Exceptions have minimal retry interval.
   */
  @Test
  public void testExceptionWithRefresh() {
    testExceptionWithRefreshAndLoader(false);
  }

  @Test
  public void testExceptionWithRefreshAsyncLoader() {
    testExceptionWithRefreshAndLoader(true);
  }

  public void testExceptionWithRefreshAndLoader(boolean asyncLoader) {
    String cacheName = generateUniqueCacheName(this);
    final int count = 4;
    final long timespan =  TestingParameters.MINIMAL_TICK_MILLIS;
    Cache2kBuilder<Integer, Integer> cb = builder(cacheName, Integer.class, Integer.class)
      .refreshAhead(true)
      .resiliencePolicy(new ExpiryTest.EnableExceptionCaching(timespan));
    if (asyncLoader) {
      cb.loader((AsyncCacheLoader<Integer, Integer>) (key, context, callback) -> {
        throw new RuntimeException("always");
      });
    } else {
      cb.loader(new BasicCacheTest.AlwaysExceptionSource());
    }
    Cache<Integer, Integer> c = cb.build();
    cache = c;
    within(timespan)
      .perform(() -> {
        for (int i = 1; i <= count; i++) {
          try {
            c.get(i);
            fail("expect exception");
          } catch (CacheLoaderException ignore) { }
        }
      })
      .expectMaybe(() -> {
        assertEquals(count, getInfo().getSize());
        assertEquals("no refresh yet", 0,
          getInfo().getRefreshCount() + getInfo().getRefreshRejectedCount()
        );
      }
      );
    await("All refreshed", () -> getInfo().getRefreshCount() + getInfo().getRefreshRejectedCount() >= count);
    assertEquals("no internal exceptions", 0, getInfo().getInternalExceptionCount());
    assertTrue("got at least 8 - submitFailedCnt exceptions",
      getInfo().getLoadExceptionCount() >= getInfo().getRefreshRejectedCount());
    await("All expired", () -> getInfo().getExpiredCount() >= count);
    assertEquals(0, getInfo().getSize());
  }

  @Test
  public void testExceptionWithRefreshSyncLoader() {
    String cacheName = generateUniqueCacheName(this);
    final int count = 4;
    final long timespan =  TestingParameters.MINIMAL_TICK_MILLIS;
    Cache<Integer, Integer> c = builder(cacheName, Integer.class, Integer.class)
      .refreshAhead(true)
      .resiliencePolicy(new ExpiryTest.EnableExceptionCaching(timespan))
      .loader(key -> {
        throw new RuntimeException("always");
      })
      .build();
    cache = c;
    within(timespan)
      .perform(() -> {
        for (int i = 1; i <= count; i++) {
          boolean gotException = false;
          try {
            c.get(i);
            fail("expect exception");
          } catch (CacheLoaderException e) {
            gotException = true;
          }
          assertTrue("got exception", gotException);
        }
      })
      .expectMaybe(() -> {
        assertEquals(count, getInfo().getSize());
        assertEquals("no refresh yet", 0,
          getInfo().getRefreshCount() + getInfo().getRefreshRejectedCount()
        );
      }
      );
    await("All refreshed", () -> getInfo().getRefreshCount() + getInfo().getRefreshRejectedCount() >= count);
    assertEquals("no internal exceptions", 0, getInfo().getInternalExceptionCount());
    assertTrue("got at least 8 - submitFailedCnt exceptions",
      getInfo().getLoadExceptionCount() >= getInfo().getRefreshRejectedCount());
    await("All expired", () -> getInfo().getExpiredCount() >= count);
    assertEquals(0, getInfo().getSize());
  }

  @Test
  public void testExceptionExpirySuppressTwiceWaitForExceptionExpiry() {
    final long exceptionExpiryMillis = TestingParameters.MINIMAL_TICK_MILLIS;
    BasicCacheTest.OccasionalExceptionSource src =
      new BasicCacheTest.PatternExceptionSource(false, true, false);
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
        .expiryPolicy((key, value1, loadTime, oldEntry) -> 0)
        .resiliencePolicy(new ResiliencePolicy<Integer, Integer>() {
          @Override
          public long suppressExceptionUntil(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo,
                                             CacheEntry<Integer, Integer> cachedEntry) {
            return loadExceptionInfo.getLoadTime() + exceptionExpiryMillis;
          }

          @Override
          public long retryLoadAfter(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo) {
            return 0;
          }
        })
        .keepDataAfterExpired(true)
        .loader(src)
        .build();
    c.get(2);
    within(exceptionExpiryMillis)
      .perform(() -> {
        c.get(2); // exception gets suppressed
      })
      .expectMaybe(() -> {
        InternalCacheInfo inf = getInfo();
        assertEquals(1, inf.getSuppressedExceptionCount());
        assertEquals(1, inf.getLoadExceptionCount());
        assertNotNull(src.key2count.get(2));
      });
    await(TestingParameters.MAX_FINISH_WAIT_MILLIS, () -> {
      c.get(2); // value is fetched, again if expired
      return src.key2count.get(2).get() == 3;
    });
  }

  @Test
  public void testExceptionExpiryNoSuppress() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
        .expiryPolicy((key, value1, loadTime, oldEntry) -> 0)
        .resiliencePolicy(
          new ExpiryTest.EnableExceptionCaching(TestingParameters.MINIMAL_TICK_MILLIS))
        .loader(src)
        .build();
    int exceptionCount = 0;
    String exceptionToString = null;
    try {
      c.get(1);
    } catch (CacheLoaderException e) {
      exceptionCount++;
      exceptionToString = e.toString();
    }
    assertEquals("1 => always exception", 1, exceptionCount);
    exceptionToString = exceptionToString.replaceAll("[0-9]", "#");
    exceptionToString = exceptionToString.replace(".##,", ".###,");
    exceptionToString = exceptionToString.replace(".#,", ".###,");
    exceptionToString = exceptionToString.replace("##:##,", "##:##.###,");
    assertEquals("org.cache#k.io.CacheLoaderException: " +
      "expiry=####-##-##T##:##:##.###, cause: " +
      "java.lang.RuntimeException: every # times", exceptionToString);
    exceptionCount = 0;
    try {
      c.get(2); // value is fetched
      c.get(2); // value is fetched again (expiry=0), but exception happens, no suppress
    } catch (CacheLoaderException e) {
      exceptionCount++;
    }
    InternalCacheInfo inf = getInfo();
    assertEquals("exception expected, no suppress", 1, exceptionCount);
    assertEquals(0, inf.getSuppressedExceptionCount());
    assertEquals(2, inf.getLoadExceptionCount());
    assertNotNull(src.key2count.get(2));
    assertEquals(2, src.key2count.get(2).get());
    await("exception expired and successful get()", () -> {
      try {
        c.get(2);
        return true;
      } catch (Exception ignore) { }
      return false;
    });
  }

  @Test
  public void testSuppressExceptionImmediateExpiry() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expiryPolicy((key, value1, loadTime, oldEntry) -> 0)
      .resiliencePolicy(new ResiliencePolicy<Integer, Integer>() {
        @Override
        public long suppressExceptionUntil(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo,
                                           CacheEntry<Integer, Integer> cachedEntry) {
          return Long.MAX_VALUE;
        }

        @Override
        public long retryLoadAfter(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo) {
          return 0;
        }
      })
      .keepDataAfterExpired(true)
      .loader(src)
      .build();
    c.get(2);
    c.get(2);
    assertEquals(1, getInfo().getSuppressedExceptionCount());
  }

  @Test
  public void testSuppressExceptionShortExpiry() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MINIMAL_TICK_MILLIS, TimeUnit.MILLISECONDS)
      .resiliencePolicy(new ResiliencePolicy<Integer, Integer>() {
        @Override
        public long suppressExceptionUntil(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo,
                                           CacheEntry<Integer, Integer> cachedEntry) {
          return loadExceptionInfo.getLoadTime() + TestingParameters.MINIMAL_TICK_MILLIS;
        }

        @Override
        public long retryLoadAfter(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo) {
          return loadExceptionInfo.getLoadTime() + TestingParameters.MINIMAL_TICK_MILLIS;
        }
      })
      .keepDataAfterExpired(true)
      .loader(src)
      .build();
    c.get(2);
    await("wait for expiry", () -> getInfo().getExpiredCount() > 0);
    c.get(2);
    assertEquals(1, getInfo().getSuppressedExceptionCount());
    assertEquals(1, getInfo().getSize());
  }

  /**
   * Test with short expiry time to trip a special case during the expiry calculation:
   * the expiry happens during the calculation
   */
  @Test
  public void testShortExpiryTimeDelayLoad() {
    boolean keepData = true;
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(1, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(keepData)
      .loader(key -> {
        sleep(2);
        return key;
      })
      .build();
    final int count = 1;
    c.get(0);
    for (int i = 0; i < count; i++) {
      c.get(i);
    }
    await("wait for expiry", () -> getInfo().getExpiredCount() >= count);
    if (keepData) {
      assertEquals(count, getInfo().getSize());
    }
  }

  /**
   * Switch keep data off.
   * Should this refuse operation right away since suppressException and keepDataAfterExpired
   * makes no sense in combination? No, since load requests should suppress exceptions, too.
   */
  @Test(expected = RuntimeException.class)
  public void testNoSuppressExceptionShortExpiry() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MINIMAL_TICK_MILLIS, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(false)
      .loader(src)
      .build();
    c.get(2);
    await(() -> getInfo().getExpiredCount() > 0);
    c.get(2);
    fail("not reached");
  }

  @Test
  public void testSuppressExceptionLongExpiryAndReload() throws ExecutionException, InterruptedException {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MINUTES)
      .resiliencePolicy(new ResiliencePolicy<Integer, Integer>() {
        @Override
        public long suppressExceptionUntil(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo,
                                           CacheEntry<Integer, Integer> cachedEntry) {
          return Long.MAX_VALUE;
        }

        @Override
        public long retryLoadAfter(Integer key, LoadExceptionInfo<Integer, Integer> loadExceptionInfo) {
          return loadExceptionInfo.getLoadTime() + TestingParameters.MINIMAL_TICK_MILLIS;
        }
      })
      .loader(src)
      .build();
    c.get(2);
    c.reloadAll(asList(2)).get();
    await(() -> getInfo().getSuppressedExceptionCount() > 0);
    c.get(2);
  }

  @Test
  public void testNeverSuppressWithRetryInterval0() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MINUTES)
      .loader(src)
      .build();
    neverSuppressBody(c);
  }

  @Test
  public void testNeverSuppressWithLoadTimeUntil() {
    BasicCacheTest.OccasionalExceptionSource src = new BasicCacheTest.OccasionalExceptionSource();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MINUTES)
      .resiliencePolicy(new ResiliencePolicy<Integer, Integer>() {
        @Override
        public long suppressExceptionUntil(Integer key, LoadExceptionInfo<Integer, Integer> exceptionInformation,
                                           CacheEntry<Integer, Integer> cachedEntry) {
          return exceptionInformation.getLoadTime();
        }

        @Override
        public long retryLoadAfter(Integer key, LoadExceptionInfo<Integer, Integer> exceptionInformation) {
          return 0;
        }
      })
      .loader(src)
      .build();
    neverSuppressBody(c);
  }

  private void neverSuppressBody(Cache<Integer, Integer> c) {
    final int key = 2;
    c.get(key);
    assertThatCode(() -> c.reloadAll(asList(key)).get())
      .getCause().isInstanceOf(CacheLoaderException.class);
    assertEquals(0, getInfo().getSuppressedExceptionCount());
    assertEquals(2, getInfo().getLoadCount());
    assertNull("Nothing stored, since retry time is 0", c.peek(key));
    assertThatCode(() -> c.get(key))
      .doesNotThrowAnyException();
    assertEquals(0, getInfo().getSuppressedExceptionCount());
    assertEquals(3, getInfo().getLoadCount());
  }

  @Test
  public void testExpireNoKeepSharpExpiryBeyondSafetyGap() {
    Cache<Integer, Integer> c = cache = builder(Integer.class, Integer.class)
      .loader(new IntCountingCacheSource())
      .expireAfterWrite(TimingUnitTest.SHARP_EXPIRY_GAP_MILLIS + 3, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(false)
      .sharpExpiry(true)
      .build();
    c.getAll(asList(1, 2, 3));
    await(() -> getInfo().getTimerEventCount() >= 3);
  }

  @Test
  public void testExpireNoKeepAsserts() {
    Cache<Integer, Integer> c = cache = builder(Integer.class, Integer.class)
      .loader(new IntCountingCacheSource())
      .expireAfterWrite(TestingParameters.MINIMAL_TICK_MILLIS, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(false)
      .build();
    within(TestingParameters.MINIMAL_TICK_MILLIS)
      .perform(() -> c.getAll(asList(1, 2, 3)))
      .expectMaybe(() -> {
        assertTrue(c.containsKey(1));
        assertTrue(c.containsKey(3));
      }
      );
    assertEquals(3, getInfo().getLoadCount());
  }

  public void testExpireNoKeep(long millis) {
    Cache<Integer, Integer> c = cache = builder(Integer.class, Integer.class)
      .loader(new IntCountingCacheSource())
      .expireAfterWrite(millis, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(false)
      .build();
    within(millis)
      .perform(() -> c.getAll(asList(1, 2, 3)))
      .expectMaybe(() -> {
        assertTrue(c.containsKey(1));
        assertTrue(c.containsKey(3));
      }
      );
    assertEquals(3, getInfo().getLoadCount());
    await(() -> getInfo().getSize() == 0);
  }

  @Test
  public void testExpireNoKeep1() {
    testExpireNoKeep(1);
  }

  @Test
  public void testExpireNoKeep2() {
    testExpireNoKeep(2);
  }

  @Test
  public void testExpireNoKeep3() {
    testExpireNoKeep(3);
  }

  @Test
  public void testExpireNoKeep77() {
    testExpireNoKeep(77);
  }

  @Test
  public void testExpireNoKeepGap() {
    testExpireNoKeep(getEffectiveSafetyGapMillis());
  }

  @Test
  public void testExpireNoKeepAfterGap() {
    testExpireNoKeep(getEffectiveSafetyGapMillis() + 3);
  }

  @Test
  public void expireLoaded_sharp_noKeep() {
    Cache<Integer, Integer> c = cache = builder(Integer.class, Integer.class)
      .loader(new IntCountingCacheSource())
      .expireAfterWrite(TestingParameters.MINIMAL_TICK_MILLIS, TimeUnit.MILLISECONDS)
      .keepDataAfterExpired(false)
      .sharpExpiry(true)
      .build();
    c.getAll(asList(1, 2, 3));
    await(() -> getInfo().getSize() == 0);
  }

  /**
   * If keepdata is true we expect some timer events.
   *
   * don't test this variant with nokeep:
   * if expiry is immediately and keepData false: refresh timer is initiated but entry
   * gets removed immediately
   */
  @Test
  public void refreshAndSharp_get_expireImmediate_keep() {
    refreshAndSharp_get(true, 0);
  }

  @Test
  public void refreshAndSharp_get_expireMinimalTick_keep() {
    refreshAndSharp_get(true, TestingParameters.MINIMAL_TICK_MILLIS);
  }

  @Test
  public void refreshAndSharp_get_expireGap_keep() {
    refreshAndSharp_get(true, getEffectiveSafetyGapMillis() + 3);
  }

  @Test
  public void refreshAndSharp_get_expireMinimalTick_noKeep() {
    refreshAndSharp_get(false, TestingParameters.MINIMAL_TICK_MILLIS);
  }

  @Test
  public void refreshAndSharp_get_expireGap_noKeep() {
    refreshAndSharp_get(false, getEffectiveSafetyGapMillis() + 3);
  }

  /**
   * Refresh ahead means entries never expire. The sharp expiry setting is a contradiction.
   * If both are set, the entry will expire at the point in time. Either the get or the refresh
   * will load the new value.
   */
  public void refreshAndSharp_get(boolean keepData, long tickTime) {
    final int key = 1;
    AtomicLong expiryTime = new AtomicLong();
    CountingLoader loader = new CountingLoader();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .sharpExpiry(true)
      .keepDataAfterExpired(keepData)
      .expiryPolicy((key1, value, startTime, currentEntry) -> {
        if (expiryTime.get() == 0) {
          expiryTime.set(startTime + tickTime);
        }
        return startTime + tickTime;
      })
      .loader(loader)
      .build();
    int v = c.get(key);
    assertTrue("expiry policy called", expiryTime.get() > 0);
    if (v == 0) {
      await("Get returns fresh", () -> {
        long t0 = ticks();
        Integer v1 = c.get(key);
        long t1 = ticks();
        assertNotNull(v1);
        assertTrue("Only see 0 before expiry time", !(v1 == 0) || t0 < expiryTime.get());
        assertTrue("Only see 1 after expiry time", !(v1 == 1) || t1 >= expiryTime.get());
        assertThat("maximum loads minus 1", v1, lessThanOrEqualTo(3));
        return v1 > 0;
      });
    } else {
      long t1 = ticks();
      assertTrue("Only see 1 after expiry time", t1 >= expiryTime.get());
    }
    final long loadsTriggeredByGet = 2;
    long additionalLoadsBecauseOfRefresh = 2;
    await("minimum loads", () -> getInfo().getLoadCount() >= loadsTriggeredByGet);
    assertThat("minimum loads triggered", getInfo().getLoadCount(),
      greaterThanOrEqualTo(loadsTriggeredByGet));
    assertThat("maximum loads triggered", getInfo().getLoadCount(),
      lessThanOrEqualTo(loadsTriggeredByGet + additionalLoadsBecauseOfRefresh));
    if (tickTime > 0) {
      await("Timer triggered", () -> getInfo().getTimerEventCount() > 0);
    }
    if (keepData) {
      await("Refresh is done", () -> getInfo().getRefreshCount() > 0);
    }
    await("Expires finally", () -> getInfo().getExpiredCount() > 0);
    await("loader count identical", () -> getInfo().getLoadCount() == loader.getCount());
  }

  /**
   * The sharp expiry switch is only used for the ExpiryPolicy and not for the
   * duration configured via expireAfterWrite.
   */
  @Test
  public void refresh_sharp_regularExpireAfterWriter_lagging() throws Exception {
    final int key = 1;
    AtomicInteger counter = new AtomicInteger();
    final long expiry = TestingParameters.MINIMAL_TICK_MILLIS;
    CountDownLatch latch = new CountDownLatch(1);
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .sharpExpiry(true)
      .expireAfterWrite(expiry, TimeUnit.MILLISECONDS)
      .loader(key1 -> {
        int v = counter.getAndIncrement();
        if (v == 1) {
          latch.await();
        }
        return v;
      })
      .build();
    within(expiry)
      .perform(() -> c.get(key))
      .expectMaybe(() -> c.containsKey(key));
    sleep(expiry * 2);
    assertTrue("still present since loader is active", c.containsKey(key));
    latch.countDown();
  }

  int value;

  public void refresh_sharp_noKeep(long expiry) {
    long maxFinishWaitMillis = TestingParameters.MAX_FINISH_WAIT_MILLIS;
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .sharpExpiry(true)
      .eternal(true)
      .keepDataAfterExpired(false)
      .expiryPolicy((key1, value, startTime, currentEntry) -> {
        if (currentEntry != null) {
          return startTime + maxFinishWaitMillis;
        }
        return startTime + expiry;
      })
      .loader(loader)
      .build();
    within(maxFinishWaitMillis + expiry)
      .perform(() -> {
        within(expiry)
          .perform(() -> value = c.get(key))
          .expectMaybe(() -> {
            assertEquals(0, value);
            assertTrue(c.containsKey(key));
          });
        await("Refresh is done", () -> getInfo().getRefreshCount() > 0);
        await("Entry disappears", () -> !c.containsKey(key));
        value = c.get(key);
      }).expectMaybe(() -> {
        assertEquals(1, value);
        assertEquals(2, loader.getCount());
      });
  }

  @Test
  public void refresh_sharp_noKeep_0ms() {
    refresh_sharp_noKeep(0);
  }

  @Test
  public void refresh_sharp_noKeep_3ms() {
    refresh_sharp_noKeep(3);
  }

  @Test
  public void refresh_sharp_keep() {
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .sharpExpiry(true)
      .eternal(true)
      .keepDataAfterExpired(true)
      .expiryPolicy((key1, value, startTime, currentEntry) -> {
        if (currentEntry != null) {
          return startTime + TestingParameters.MAX_FINISH_WAIT_MILLIS;
        }
        return startTime + TestingParameters.MINIMAL_TICK_MILLIS;
      })
      .loader(loader)
      .build();
    within(TestingParameters.MAX_FINISH_WAIT_MILLIS)
      .perform(() -> {
        AtomicInteger v = new AtomicInteger();
        within(TestingParameters.MINIMAL_TICK_MILLIS)
          .perform(() -> {
            v.set(c.get(key));
          })
          .expectMaybe(() -> {
            assertEquals(0, v.get());
            assertTrue(c.containsKey(key));
          });
        await("Refresh is done", () -> getInfo().getRefreshCount() > 0);
        await("Entry disappears", () -> !c.containsKey(key));
      })
      .expectMaybe(() -> {
        int v = c.get(key);
        assertEquals("long expiry after refresh", 1, v);
        assertEquals(2, loader.getCount());
      });
  }

  /**
   * Refresh is started immediately if loaded value expired immediately.
   */
  @Test
  public void refresh_immediate() {
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .eternal(true)
      .keepDataAfterExpired(false)
      .expiryPolicy((key1, value, startTime, currentEntry) -> {
        if (currentEntry != null) {
          return ExpiryTimeValues.ETERNAL;
        }
        return ExpiryTimeValues.REFRESH;
      })
      .loader(loader)
      .build();
    int v = c.get(key);
    if (isWiredCache()) {
      assertEquals("loaded value always returned in WiredCache", 0, v);
    }
    await("Refresh is done", () -> loader.getCount() == 2);
  }

  @Test
  public void refresh_sharp_noKeep_eternalAfterRefresh() throws Exception {
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .sharpExpiry(true)
      .eternal(true)
      .keepDataAfterExpired(false)
      .expiryPolicy((key1, value, loadTime, currentEntry) -> {
        if (currentEntry != null) {
          return ExpiryTimeValues.ETERNAL;
        }
        return loadTime + TestingParameters.MINIMAL_TICK_MILLIS;
      })
      .loader(loader)
      .build();
    AtomicInteger v = new AtomicInteger();
    within(TestingParameters.MINIMAL_TICK_MILLIS)
      .perform(() ->
        v.set(c.get(key)))
      .expectMaybe(() ->
        assertEquals("loaded value expected when within time range", 0, v.get()));
    if (isWiredCache()) {
      assertEquals("loaded value always returned in WiredCache", 0, v.get());
    }
    await("Refresh is done", () -> loader.getCount() == 2);
    assertFalse("Entry disappears", c.containsKey(key));
    assertEquals(1, (int) c.get(key));
    assertEquals(2, loader.getCount());
    assertTrue("Entry appears", c.containsKey(key));
  }

  /**
   * Is refreshing stopped after a remove? Checks whether the timer is cancelled.
   */
  @Test
  public void refresh_timerStoppedWithRemove() throws InterruptedException {
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    final long expiry = 123;
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .expireAfterWrite(expiry, TimeUnit.MILLISECONDS)
      .loader(loader)
      .build();
    long t0 = ticks();
    c.get(key);
    sleep(expiry * 3 / 2);
    c.remove(key);
    if (ticks() - t0 < expiry * 2) {
      sleep(expiry * 3 - (ticks() - t0));
      assertThat(
        "0 timer events if we are to fast, " +
        "max. 1 timer event because entry was removed before the refresh could happen",
        getInfo().getTimerEventCount(), isOneOf(0L, 1L));
    }
  }

  @Test
  public void refresh_secondTimerEvent_allIsCleared() throws InterruptedException {
    final int key = 1;
    CountingLoader loader = new CountingLoader();
    final long expiry = 123;
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .refreshAhead(true)
      .expireAfterWrite(expiry, TimeUnit.MILLISECONDS)
      .loader(loader)
      .keepDataAfterExpired(false)
      .build();
    c.get(key);
    await(() -> getInfo().getTimerEventCount() == 2);
    await(() -> getInfo().getSize() == 0);
    assertEquals(2, loader.getCount());
  }

  @Test
  public void loadAndExpireRaceNoGone() {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .keepDataAfterExpired(false)
      .expireAfterWrite(TestingParameters.MINIMAL_TICK_MILLIS, TimeUnit.MILLISECONDS)
      .loader(key -> {
        sleep(100);
        return key;
      })
      .build();
    c.put(1, 1);
    c.reloadAll(asList(1));
    await(() -> getInfo().getLoadCount() > 0);
    await(() -> getInfo().getSize() == 0);
  }

  @Test
  public void manualExpire_nowIsh() {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(5, TimeUnit.MINUTES)
      .build();
    c.put(1, 2);
    c.expireAt(1, ticks() + TestingParameters.MINIMAL_TICK_MILLIS);
    await(() -> !c.containsKey(1));
  }

  @Test
  public void expireAt_nowIsh_doesRefresh() {
    expireAt_x_doesRefresh(ticks() + TestingParameters.MINIMAL_TICK_MILLIS);
  }

  @Test
  public void expireAt_NOW_doesRefresh() {
    expireAt_x_doesRefresh(ticks());
  }

  @Test
  public void expireAt_1234_doesRefresh() {
    expireAt_x_doesRefresh(1234);
  }

  private void expireAt_x_doesRefresh(long x) {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MILLISECONDS)
      .refreshAhead(true)
      .loader(key -> 4711)
      .build();
    c.put(1, 2);
    c.expireAt(1, x);
    await(() -> (!c.containsKey(1) && c.get(1) == 4711));
    await(() -> getInfo().getRefreshCount() == 1);
  }

  @Test
  public void manualExpire_NOW_doesNotRefresh() {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MILLISECONDS)
      .refreshAhead(true)
      .loader(key -> 4711)
      .build();
    c.put(1, 2);
    c.invoke(1, entry -> entry.setExpiryTime(ExpiryTimeValues.NOW));
    sleep(0);
    sleep(0);
    sleep(0);
    assertNull("no refresh (v2.0)", c.peek(1));
    assertEquals("no refresh (v2.0)", 0, getInfo().getRefreshCount());
  }

  @Test
  public void manualExpire_REFRESH_doesRefresh() {
    AtomicInteger counter = new AtomicInteger();
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(TestingParameters.MAX_FINISH_WAIT_MILLIS, TimeUnit.MILLISECONDS)
      .refreshAhead(true)
      .loader(key -> {
        counter.incrementAndGet();
        return 4711;
      })
      .build();
    c.put(1, 2);
    c.invoke(1, entry -> entry.setExpiryTime(ExpiryTimeValues.REFRESH));
    await(() -> counter.get() > 0);
  }

  /**
   * Check whether raising lag time has effect.
   */
  @Test
  public void timerLag_raisedLag() {
    long lagMillis = DefaultTimer.DEFAULT_TIMER_LAG_MILLIS + 74;
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .expireAfterWrite(1, TimeUnit.MILLISECONDS)
      .timerLag(lagMillis, TimeUnit.MILLISECONDS)
      .build();
    within(lagMillis).perform(() -> c.put(1, 1)).expectMaybe(() -> {
      sleep(lagMillis - 1);
      assertTrue(c.containsKey(1));
    });
  }

  @Test
  public void neutralWhenModified() throws Exception {
    final long expiry = 100;
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .sharpExpiry(true)
      .expiryPolicy((key, value, startTime, currentEntry) -> {
        if (currentEntry == null) {
          return startTime + expiry;
        }
        return ExpiryTimeValues.NEUTRAL;
      })
      .build();
    within(expiry)
      .perform(() -> {
        c.put(1, 2);
        c.put(1, 2);
        c.put(1, 2);
      })
      .expectMaybe(() -> assertTrue(c.containsKey(1)));
    sleep(expiry);
    assertFalse(c.containsKey(1));
  }

  @Test(expected = IllegalArgumentException.class)
  public void neutralWhenCreatedYieldsException() throws Exception {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .sharpExpiry(true)
      .expiryPolicy((key, value, startTime, currentEntry) -> ExpiryTimeValues.NEUTRAL)
      .build();
    c.put(1, 2);
  }

  @Test
  public void expiryPolicy_dontCache_load_exception() {
    Cache<Integer, Integer> c = builder(Integer.class, Integer.class)
      .sharpExpiry(true)
      .expiryPolicy((key, value, loadTime, oldEntry) -> loadTime + 100)
      .loader(key -> { throw new RuntimeException(); })
      .build();
    try {
      c.get(123);
      fail();
    } catch (CacheLoaderException ex1) {
      try {
        c.get(123);
        fail();
      } catch (CacheLoaderException ex2) {
        assertNotSame(ex1.getCause(), ex2.getCause());
        assertTrue(ex1.getCause() instanceof RuntimeException);
      }
    }
  }

}

