package org.cache2k.test.core;

/*
 * #%L
 * cache2k core
 * %%
 * Copyright (C) 2000 - 2018 headissue GmbH, Munich
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

import org.cache2k.Cache;
import org.cache2k.core.ExceptionWrapper;
import org.cache2k.core.StandardExceptionPropagatorTest;
import org.cache2k.integration.CacheLoaderException;
import org.cache2k.testing.category.FastTests;
import org.cache2k.processor.EntryProcessingException;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.processor.MutableCacheEntry;
import org.cache2k.test.util.IntCacheRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

/**
 * Test various places where an exception must be thrown when an entry is accessed.
 *
 * @author Jens Wilke
 * @see StandardExceptionPropagatorTest
 */
@Category(FastTests.class)
public class ExceptionPropagatorTest {

  final static Integer KEY = 1;

  /** Provide unique standard cache per method */
  @Rule public IntCacheRule target = new IntCacheRule();

  @Test(expected = CacheLoaderException.class)
  public void peekAndRemove_throws() {
    prepCache().peekAndRemove(KEY);
  }

  @Test(expected = CacheLoaderException.class)
  public void peek_throws() {
    prepCache().peek(KEY);
  }

  @Test(expected = CacheLoaderException.class)
  public void get_throws() {
    prepCache().get(KEY);
  }

  @Test(expected = CacheLoaderException.class)
  public void getEntry_entry_throws() {
    prepCache().getEntry(KEY).getValue();
  }

  @Test(expected = CacheLoaderException.class)
  public void peekEntry_entry_throws() {
    prepCache().peekEntry(KEY).getValue();
  }

  @Test(expected = CacheLoaderException.class)
  public void iterator_entry_throws() {
    prepCache().entries().iterator().next().getValue();
  }

  @Test
  public void mutableEntry_entry_throws() {
    try {
      prepCache().invoke(KEY, new EntryProcessor<Integer, Integer, Void>() {
        @Override
        public Void process(final MutableCacheEntry<Integer, Integer> e) throws Exception {
          e.getValue();
          return null;
        }
      });
      fail();
    } catch (EntryProcessingException ex) {
      assertEquals(CacheLoaderException.class, ex.getCause().getClass());
    }
  }

  Cache<Integer, Integer> prepCache() {
    Cache c = target.cache();
    c.put(KEY, new ExceptionWrapper(new IllegalArgumentException("Test")));
    assertTrue(c.containsKey(KEY));
    return c;
  }

}
