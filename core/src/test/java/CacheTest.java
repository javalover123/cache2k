/*
 * #%L
 * cache2k core package
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
import org.cache2k.Cache;
import org.cache2k.CacheBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.CacheSource;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Basic sanity checks and examples.
 *
 * @author Jens Wilke; created: 2013-12-17
 */
public class CacheTest {

  @Test
  public void testPeekAndPut() {
    Cache<String,String> c =
      CacheBuilder.newCache(String.class, String.class).build();
    String val = c.peek("something");
    c.put("something", "hello");
    val = c.get("something");
    c.close();
  }

  @Test
  public void testGetWithSource() {
    CacheSource<String,Integer> _lengthCountingSource = new CacheSource<String, Integer>() {
      @Override
      public Integer get(String o) throws Throwable {
        return o.length();
      }
    };
    Cache<String,Integer> c =
      CacheBuilder.newCache(String.class, Integer.class)
        .source(_lengthCountingSource)
        .build();
    int v = c.get("hallo");
    v = c.get("long string");
    c.close();
  }

  @Test
  public void testGetEntry() {
    Cache<String,String> c =
      CacheBuilder.newCache(String.class, String.class).build();
    String val = c.peek("something");
    c.put("something", "hello");
    CacheEntry<String, String> e = c.getEntry("something");
    c.close();
  }

  @Test
  public void testContains() {
    Cache<String,String> c =
      CacheBuilder.newCache(String.class, String.class).build();
    String val = c.peek("something");
    c.put("something", "hello");
    c.close();
  }

}
