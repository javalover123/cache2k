package org.cache2k.core;

/*
 * #%L
 * cache2k core implementation
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

import org.cache2k.Cache;
import org.cache2k.CacheException;
import org.cache2k.annotation.Nullable;
import org.cache2k.config.CacheType;
import org.cache2k.core.api.CommonMetrics;
import org.cache2k.core.api.InternalCacheInfo;
import org.cache2k.core.eviction.Eviction;
import org.cache2k.core.timing.Timing;
import org.cache2k.io.AsyncBulkCacheLoader;
import org.cache2k.io.BulkCacheLoader;
import org.cache2k.operation.TimeReference;
import org.cache2k.event.CacheEntryEvictedListener;
import org.cache2k.event.CacheEntryExpiredListener;
import org.cache2k.io.AdvancedCacheLoader;
import org.cache2k.CacheEntry;
import org.cache2k.event.CacheEntryCreatedListener;
import org.cache2k.io.AsyncCacheLoader;
import org.cache2k.io.ExceptionPropagator;
import org.cache2k.processor.EntryProcessingException;
import org.cache2k.processor.EntryProcessingResult;
import org.cache2k.processor.EntryProcessor;
import org.cache2k.event.CacheEntryRemovedListener;
import org.cache2k.event.CacheEntryUpdatedListener;
import org.cache2k.CacheManager;
import org.cache2k.io.CacheWriter;
import org.cache2k.CacheOperationCompletionListener;
import org.cache2k.core.operation.ExaminationEntry;
import org.cache2k.core.operation.Semantic;
import org.cache2k.core.operation.Operations;
import org.cache2k.core.log.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

/**
 * A cache implementation that builds on a heap cache and coordinates with additional
 * attachments like storage, listeners and a writer.
 *
 * @author Jens Wilke
 */
public class WiredCache<K, V> extends BaseCache<K, V>
  implements HeapCacheListener<K, V> {

  @SuppressWarnings("unchecked")
  final Operations<K, V> ops = Operations.SINGLETON;

  /** Either ourselves or a wrapped cache used for events */
  Cache<K, V> userCache;
  HeapCache<K, V> heapCache;
  AdvancedCacheLoader<K, V> loader;
  AsyncCacheLoader<K, V> asyncLoader;
  BulkCacheLoader<K, V> bulkCacheLoader;
  CacheWriter<K, V> writer;
  CacheEntryRemovedListener<K, V>[] syncEntryRemovedListeners;
  CacheEntryCreatedListener<K, V>[] syncEntryCreatedListeners;
  CacheEntryUpdatedListener<K, V>[] syncEntryUpdatedListeners;
  CacheEntryExpiredListener<K, V>[] syncEntryExpiredListeners;
  CacheEntryEvictedListener<K, V>[] syncEntryEvictedListeners;

  private CommonMetrics.Updater metrics() {
    return heapCache.metrics;
  }

  @Override
  public Log getLog() {
    return heapCache.getLog();
  }

  /** For testing */
  public HeapCache getHeapCache() {
    return heapCache;
  }

  @Override
  public TimeReference getClock() {
    return heapCache.getClock();
  }

  @Override
  public boolean isNullValuePermitted() {
    return heapCache.isNullValuePermitted();
  }

  @Override
  public String getName() {
    return heapCache.getName();
  }

  @Override
  public CacheType getKeyType() {
    return heapCache.getKeyType();
  }

  @Override
  public CacheType getValueType() {
    return heapCache.getValueType();
  }

  @Override
  public CacheManager getCacheManager() {
    return heapCache.getCacheManager();
  }

  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> function) {
    return returnValue(execute(key, ops.computeIfAbsent(key, function)));
  }

  @Override
  public V peekAndPut(K key, V value) {
    return returnValue(execute(key, ops.peekAndPut(key, value)));
  }

  @Override
  public V peekAndRemove(K key) {
    return returnValue(execute(key, ops.peekAndRemove(key)));
  }

  @Override
  public V peekAndReplace(K key, V value) {
    return returnValue(execute(key, ops.peekAndReplace(key, value)));
  }

  @Override
  public CacheEntry<K, V> peekEntry(K key) {
    return execute(key, ops.peekEntry());
  }

  @Override
  public boolean containsKey(K key) {
    return execute(key, ops.contains(key));
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    return execute(key, ops.putIfAbsent(key, value));
  }

  @Override
  public void put(K key, V value) {
    execute(key, ops.put(key, value));
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> m) {
    for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  @Override
  public void remove(K key) {
    execute(key, ops.remove(key));
  }

  @Override
  public boolean removeIfEquals(K key, V value) {
    return execute(key, ops.remove(key, value));
  }

  @Override
  public boolean containsAndRemove(K key) {
    return execute(key, ops.containsAndRemove(key));
  }

  @Override
  public boolean replace(K key, V newValue) {
    return execute(key, ops.replace(key, newValue));
  }

  @Override
  public boolean replaceIfEquals(K key, V oldValue, V newValue) {
    return execute(key, ops.replace(key, oldValue, newValue));
  }

  @Override
  public void loadAll(Iterable<? extends K> keys, CacheOperationCompletionListener l) {
    checkLoaderPresent();
    CacheOperationCompletionListener listener =
      l != null ? l : HeapCache.DUMMY_LOAD_COMPLETED_LISTENER;
    Set<K> keysToLoad = heapCache.checkAllPresent(keys);
    if (keysToLoad.isEmpty()) { listener.onCompleted(); return; }
    if (asyncLoader != null) {
      asyncBulkOp(Operations.GET, listener, keysToLoad);
    } else {
      if (bulkCacheLoader == null) {
        loadAllWithSyncLoader(listener, keysToLoad);
      } else {
        executeSyncBulkOp(Operations.GET, keysToLoad, listener);
      }
    }
  }

  private void executeSyncBulkOp(Semantic operation, Set<K> keys,
                                 CacheOperationCompletionListener listener) {
    Runnable runnable = () -> {
      Throwable t;
      try {
        AsyncBulkAction<K, V, V> result = syncBulkOp(operation, keys);
        t = result.getExceptionToPropagate();
      } catch (Throwable maybeCacheClosedException) {
        t = maybeCacheClosedException;
      }
      if (t != null) {
        listener.onException(t);
      } else {
        listener.onCompleted();
      }
    };
    heapCache.executeLoader(runnable);
  }

  /**
   * Process operation synchronously and use the bulk loader, if possible.
   */
  private <R> AsyncBulkAction<K, V, R> syncBulkOp(Semantic<K, V, R> op,
                                                  Set<? extends K> keys) {
    AsyncBulkAction<K, V, R> bulkAction = new AsyncBulkAction<>();
    Set<EntryAction<K, V, R>> actions = new HashSet<>();
    for (K k : keys) {
      actions.add(new MyEntryAction<R>(op, k, null) {
        @Override
        protected AsyncCacheLoader<K, V> asyncLoader() {
          return bulkAction;
        }
      });
    }
    bulkAction.start((AsyncBulkCacheLoader<K, V>) (keySet, contexts, callback) -> {
      try {
        callback.onLoadSuccess(bulkCacheLoader.loadAll(keySet));
      } catch (Throwable ouch) {
        callback.onLoadFailure(ouch);
      }
    }, actions);
    return bulkAction;
  }

  /**
   * Process operation asynchronously and use the bulk loader, if possible.
   */
  private void asyncBulkOp(Semantic<K, V, Void> op,
                           CacheOperationCompletionListener listener,
                           Set<K> keysToLoad) {
    AsyncBulkAction<K, V, Void> bulkAction = new AsyncBulkAction<K, V, Void>() {
      @Override
      protected void bulkOperationCompleted() {
        Throwable exception = getExceptionToPropagate();
        if (exception != null) {
          listener.onException(exception);
        } else {
          listener.onCompleted();
        }
      }
    };
    Set<EntryAction<K, V, Void>> actions = new HashSet<>();
    for (K k : keysToLoad) {
      actions.add(new MyEntryAction<Void>(op, k, null, bulkAction) {
          @Override
          protected AsyncCacheLoader<K, V> asyncLoader() {
            return bulkAction;
          }
        }
      );
    }
    bulkAction.start(asyncLoader, actions);
  }

  /**
   * Only sync loader present. Use loader executor to run things in parallel.
   */
  private void loadAllWithSyncLoader(CacheOperationCompletionListener listener,
                                     Set<K> keysToLoad) {
    OperationCompletion<K> completion = new OperationCompletion<K>(keysToLoad, listener);
    for (K key : keysToLoad) {
      heapCache.executeLoader(completion, key, () -> {
        execute(key, null, ops.get(key));
        EntryAction<K, V, V> action = createEntryAction(key, null, ops.get(key));
        action.start();
        return action.getException();
      });
    }
  }

  @Override
  public void reloadAll(Iterable<? extends K> keys, CacheOperationCompletionListener l) {
    checkLoaderPresent();
    CacheOperationCompletionListener listener =
      l != null ? l : HeapCache.DUMMY_LOAD_COMPLETED_LISTENER;
    Set<K> keySet = heapCache.generateKeySet(keys);
    if (asyncLoader != null) {
      asyncBulkOp(ops.unconditionalLoad, listener, keySet);
    } else {
      if (bulkCacheLoader == null) {
        reloadAllWithSyncLoader(listener, keySet);
      } else {
        executeSyncBulkOp(ops.unconditionalLoad, keySet, l);
      }
    }
  }

  private void reloadAllWithSyncLoader(CacheOperationCompletionListener listener,
                                       Set<K> keysToLoad) {
    OperationCompletion<K> completion = new OperationCompletion<>(keysToLoad, listener);
    for (K key : keysToLoad) {
      heapCache.executeLoader(completion, key, () -> {
        EntryAction<K, V, V> action = createEntryAction(key, null, ops.unconditionalLoad);
        action.start();
        return action.getException();
      });
    }
  }

  protected <R> MyEntryAction<R> createFireAndForgetAction(Entry<K, V> e, Semantic<K, V, R> op) {
    return new MyEntryAction<R>(op, e.getKey(), e, EntryAction.NOOP_CALLBACK);
  }

  @Override
  public Executor getExecutor() {
    return heapCache.getExecutor();
  }

  private void checkLoaderPresent() {
    if (!isLoaderPresent()) {
      throw new UnsupportedOperationException("loader not set");
    }
  }

  @Override
  public boolean isWeigherPresent() {
    return heapCache.eviction.isWeigherPresent();
  }

  @Override
  public boolean isLoaderPresent() {
    return loader != null || asyncLoader != null;
  }

  V returnValue(V v) {
    return heapCache.returnValue(v);
  }

  V returnValue(Entry<K, V> e) {
    return returnValue(e.getValueOrException());
  }

  Entry<K, V> lookupQuick(K key) {
    return heapCache.lookupEntry(key);
  }


  @Override
  public V get(K key) {
    Entry<K, V> e = lookupQuick(key);
    if (e != null && e.hasFreshData(getClock())) {
      return returnValue(e);
    }
    return returnValue(execute(key, e, ops.get(key)));
   }

  /**
   * Just a simple loop at the moment. We need to deal with possible null values
   * and exceptions. This is a simple placeholder implementation that covers it
   * all by working on the entry.
   */
  @Override
  public Map<K, V> getAll(Iterable<? extends K> keys) {
    if (bulkCacheLoader == null) {
      return getAllSequential(keys);
    } else {
      Set<K> keySet = heapCache.generateKeySet(keys);
      AsyncBulkAction<K, V, V> result = syncBulkOp(ops.GET_ENTRY, keySet);
      Throwable t = result.getExceptionToPropagate();
      if (t != null) {
        if (t instanceof RuntimeException) {
          throw (RuntimeException) t;
        } else if (t instanceof Error) {
          throw (Error) t;
        }
        throw new CacheException(t);
      }
      return result.getResultMap();
    }
  }

  private Map<K, V> getAllSequential(Iterable<? extends K> keys) {
    /* copied from reloadAll:
    OperationCompletion<K> completion = new OperationCompletion<>(keysToLoad, listener);
    for (K key : keysToLoad) {
      heapCache.executeLoader(completion, key, () -> {
        EntryAction<K, V, V> action = createEntryAction(key, null, ops.unconditionalLoad);
        action.start();
        return action.getException();
      });
    }
    */
    Map<K, CacheEntry<K, V>> map = new HashMap<K, CacheEntry<K, V>>();
    for (K k : keys) {
      CacheEntry<K, V> e = execute(k, ops.getEntry(k));
      if (e != null) {
        map.put(k, e);
      }
    }
    return heapCache.convertCacheEntry2ValueMap(map);
  }

  @Override
  public CacheEntry<K, V> getEntry(K key) {
    return execute(key, ops.getEntry(key));
  }

  @Override
  public int getTotalEntryCount() {
    return heapCache.getTotalEntryCount();
  }

  @Override
  public <R> R invoke(K key, EntryProcessor<K, V, R> processor) {
    if (key == null) {
      throw new NullPointerException();
    }
    return execute(key, ops.invoke(processor));
  }

  @Override
  public <@Nullable R> Map<K, EntryProcessingResult<R>> invokeAll(Iterable<? extends K> keys,
                                                                  EntryProcessor<K, V, R> entryProcessor) {
    if (bulkCacheLoader == null) {
      return super.invokeAll(keys, entryProcessor);
    }
    Set<K> keySet = heapCache.generateKeySet(keys);
    AsyncBulkAction<K, V, R> actionResult = syncBulkOp(ops.invoke(entryProcessor), keySet);
    Map<K, EntryProcessingResult<R>> resultMap = new HashMap<>();
    for (EntryAction<K, V, R> action : actionResult.getActions()) {
      EntryProcessingResult<R> singleResult;
      Throwable exception = action.getException();
      if (exception == null) {
        R value = action.result;
        singleResult = new EntryProcessingResult<R>() {
          @Override
          public @Nullable R getResult() { return value; }
          @Override
          public @Nullable Throwable getException() { return null; }
        };
      } else {
        singleResult = new EntryProcessingResult<R>() {
          @Override
          public @Nullable R getResult() { throw new EntryProcessingException(exception); }
          @Override
          public @Nullable Throwable getException() { return exception; }
        };
      }
      resultMap.put(action.getKey(), singleResult);
    }
    return resultMap;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterator<CacheEntry<K, V>> iterator() {
    Iterator<CacheEntry<K, V>> it = new HeapCache.IteratorFilterEntry2Entry(
      heapCache, heapCache.iterateAllHeapEntries(), true);
    Iterator<CacheEntry<K, V>> adapted = new Iterator<CacheEntry<K, V>>() {

      CacheEntry<K, V> entry;

      @Override
      public boolean hasNext() {
        return it.hasNext();
      }

      @Override
      public CacheEntry<K, V> next() {
        return entry = it.next();
      }

      @Override
      public void remove() {
        if (entry == null) {
          throw new IllegalStateException("call next first");
        }
        WiredCache.this.remove(entry.getKey());
      }
    };
    return adapted;
  }

  @Override
  public V peek(K key) {
    Entry<K, V> e = lookupQuick(key);
    if (e != null && e.hasFreshData(getClock())) {
      return returnValue(e);
    }
    return returnValue(execute(key, ops.peek(key)));
  }

  /**
   * We need to deal with possible null values and exceptions. This is
   * a simple placeholder implementation that covers it all by working
   * on the entry.
   */
  @Override
  public Map<K, V> peekAll(Iterable<? extends K> keys) {
    Map<K, CacheEntry<K, V>> map = new HashMap<K, CacheEntry<K, V>>();
    for (K k : keys) {
      CacheEntry<K, V> e = execute(k, ops.peekEntry());
      if (e != null) {
        map.put(k, e);
      }
    }
    return heapCache.convertCacheEntry2ValueMap(map);
  }

  @Override
  public InternalCacheInfo getLatestInfo() {
    return heapCache.getLatestInfo(this);
  }

  @Override
  public InternalCacheInfo getInfo() {
    return heapCache.getInfo(this);
  }

  @Override
  public CommonMetrics getCommonMetrics() {
    return heapCache.getCommonMetrics();
  }

  @Override
  public void logAndCountInternalException(String s, Throwable t) {
    heapCache.logAndCountInternalException(s, t);
  }

  @Override
  public void checkIntegrity() {
    heapCache.checkIntegrity();
  }

  @Override
  public boolean isClosed() {
    return heapCache.isClosed();
  }

  public void init() {
    heapCache.timing.setTarget(this);
    heapCache.initWithoutTimerHandler();
  }

  @Override
  public void cancelTimerJobs() {
    synchronized (lockObject()) {
      heapCache.cancelTimerJobs();
    }
  }

  @Override
  public void clear() {
    heapCache.clear();
  }

  @Override
  public void close() {
    try {
      heapCache.closePart1();
    } catch (CacheClosedException ex) {
      return;
    }
    heapCache.closePart2(this);
    closeCustomization(writer, "writer");
    if (syncEntryCreatedListeners != null) {
      for (Object l : syncEntryCreatedListeners) {
        closeCustomization(l, "entryCreatedListener");
      }
    }
    if (syncEntryUpdatedListeners != null) {
      for (Object l : syncEntryUpdatedListeners) {
        closeCustomization(l, "entryUpdatedListener");
      }
    }
    if (syncEntryRemovedListeners != null) {
      for (Object l : syncEntryRemovedListeners) {
        closeCustomization(l, "entryRemovedListener");
      }
    }
    if (syncEntryExpiredListeners != null) {
      for (Object l : syncEntryExpiredListeners) {
        closeCustomization(l, "entryExpiredListener");
      }
    }
  }

  @Override
  public CacheEntry<K, V> returnCacheEntry(ExaminationEntry<K, V> e) {
    return heapCache.returnCacheEntry(e);
  }

  private Object lockObject() {
    return heapCache.lock;
  }

  @Override
  public Eviction getEviction() {
    return heapCache.getEviction();
  }

  /**
   * Calls eviction listeners.
   */
  @Override
  public void onEvictionFromHeap(Entry<K, V> e) {
    CacheEntry<K, V> currentEntry = heapCache.returnCacheEntry(e);
    if (syncEntryEvictedListeners != null) {
      for (CacheEntryEvictedListener<K, V> l : syncEntryEvictedListeners) {
        try {
          l.onEntryEvicted(getUserCache(), currentEntry);
        } catch (Throwable t) {
          getLog().warn("Exception from eviction listener", t);
        }
      }
    }
  }

  @Override
  protected <R> EntryAction<K, V, R> createEntryAction(K key, Entry<K, V> e, Semantic<K, V, R> op) {
    return new MyEntryAction<R>(op, key, e);
  }

  @Override
  public String getEntryState(K key) {
    return heapCache.getEntryState(key);
  }

  /**
   * If not expired yet, negate time to enforce time checks, schedule task for expiry
   * otherwise.
   *
   * Semantics double with {@link HeapCache#timerEventExpireEntry(Entry, Object)}
   */
  @Override
  public void timerEventExpireEntry(Entry<K, V> e, Object task) {
    metrics().timerEvent();
    synchronized (e) {
      if (e.getTask() != task) { return; }
      long nrt = e.getNextRefreshTime();
      long now = heapCache.clock.millis();
      if (now < Math.abs(nrt)) {
        if (nrt > 0) {
          heapCache.timing.scheduleFinalTimerForSharpExpiry(e);
          e.setNextRefreshTime(-nrt);
        }
        return;
      }
    }
    enqueueTimerAction(e, ops.expireEvent);
  }

  private <R> void enqueueTimerAction(Entry<K, V> e, Semantic<K, V, R> op) {
    EntryAction<K, V, R> action = createFireAndForgetAction(e, op);
    getExecutor().execute(action);
  }

  /**
   * Starts a refresh operation or expires if no threads in the loader thread pool are available.
   * If no async loader is available we execute the synchronous loader via the loader
   * thread pool.
   */
  @Override
  public void timerEventRefresh(Entry<K, V> e, Object task) {
    metrics().timerEvent();
    synchronized (e) {
      if (e.getTask() != task) { return; }
      if (asyncLoader != null) {
        enqueueTimerAction(e, ops.refresh);
        return;
      }
      try {
        heapCache.refreshExecutor.execute(createFireAndForgetAction(e, ops.refresh));
      } catch (RejectedExecutionException ex) {
        metrics().refreshRejected();
        enqueueTimerAction(e, ops.expireEvent);
      }
    }
  }

  @Override
  public void timerEventProbationTerminated(Entry<K, V> e, Object task) {
    metrics().timerEvent();
    synchronized (e) {
      if (e.getTask() != task) { return; }
    }
    enqueueTimerAction(e, ops.expireEvent);
  }

  @Override
  public Cache<K, V> getUserCache() {
    return userCache;
  }

  /**
   * Wire the entry action to the resources of this cache.
   */
  class MyEntryAction<R> extends EntryAction<K, V, R> {

    MyEntryAction(Semantic<K, V, R> op, K k, Entry<K, V> e) {
      super(WiredCache.this.heapCache, WiredCache.this, op, k, e);
    }

    MyEntryAction(Semantic<K, V, R> op, K k,
                  Entry<K, V> e, CompletedCallback cb) {
      super(WiredCache.this.heapCache, WiredCache.this, op, k, e, cb);
    }

    @Override
    protected boolean mightHaveListeners() {
      return true;
    }

    @Override
    protected CacheEntryCreatedListener<K, V>[] entryCreatedListeners() {
      return syncEntryCreatedListeners;
    }

    @Override
    protected CacheEntryRemovedListener<K, V>[] entryRemovedListeners() {
      return syncEntryRemovedListeners;
    }

    @Override
    protected CacheEntryUpdatedListener<K, V>[] entryUpdatedListeners() {
      return syncEntryUpdatedListeners;
    }

    @Override
    protected CacheEntryExpiredListener<K, V>[] entryExpiredListeners() {
      return syncEntryExpiredListeners;
    }

    @Override
    protected CacheWriter<K, V> writer() {
      return writer;
    }

    @Override
    protected Timing<K, V> timing() {
      return heapCache.timing;
    }

    /**
     * Provides async loader context
     *
     * @see AsyncCacheLoader.Context
     */
    @Override
    public Executor getLoaderExecutor() {
      return heapCache.loaderExecutor;
    }

    @Override
    protected AsyncCacheLoader<K, V> asyncLoader() {
      return asyncLoader;
    }

    @Override
    protected Executor executor() { return heapCache.getExecutor(); }

    /**
     * Provides async loader context
     *
     * @see AsyncCacheLoader.Context
     */
    @Override
    public Executor getExecutor() {
      return executor();
    }

    @Override
    public ExceptionPropagator getExceptionPropagator() {
      return heapCache.exceptionPropagator;
    }

  }

}
