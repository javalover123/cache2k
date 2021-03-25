package org.cache2k.addon;

/*
 * #%L
 * cache2k addon
 * %%
 * Copyright (C) 2000 - 2021 headissue GmbH, Munich
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
import org.cache2k.config.CacheBuildContext;
import org.cache2k.config.ToggleFeature;
import org.cache2k.config.WithSection;
import org.cache2k.io.AsyncBulkCacheLoader;
import org.cache2k.io.AsyncCacheLoader;

/**
 * @author Jens Wilke
 */
public class CoalescingBulkLoaderSupport extends ToggleFeature
  implements WithSection<CoalescingBulkLoaderConfig, CoalescingBulkLoaderConfig.Builder>  {

  private static CoalescingBulkLoaderConfig DEFAULT_CONFIG = new CoalescingBulkLoaderConfig();

  public static CoalescingBulkLoaderSupport enable(Cache2kBuilder<?, ?> b) {
    return ToggleFeature.enable(b, CoalescingBulkLoaderSupport.class);
  }

  public static void disable(Cache2kBuilder<?, ?> b) {
    b.disable(CoalescingBulkLoaderSupport.class);
  }

  @Override
  protected void doEnlist(CacheBuildContext<?, ?> ctx) {
    AsyncCacheLoader asyncCacheLoader = ctx.createCustomization(ctx.getConfig().getAsyncLoader());
    if (!(asyncCacheLoader instanceof AsyncBulkCacheLoader)) {
      throw new IllegalArgumentException(this.getClass().getName()
        + " requires a configure bulk loader");
    }
    AsyncBulkCacheLoader finalAsyncBulkCacheLoader = (AsyncBulkCacheLoader) asyncCacheLoader;
    CoalescingBulkLoaderConfig config =
      ctx.getConfig().getSections().getSection(CoalescingBulkLoaderConfig.class, DEFAULT_CONFIG);
    ctx.getConfig().builder().bulkLoader(
      new CoalescingBulkLoader(finalAsyncBulkCacheLoader, ctx.getTimeReference(),
        config.getMaxDelay(), config.getMaxLoadSize())
    );
  }

  @Override
  public Class<CoalescingBulkLoaderConfig> getConfigClass() {
    return CoalescingBulkLoaderConfig.class;
  }
}
