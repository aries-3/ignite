/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.ignite.internal.processors.cache;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import javax.cache.CacheException;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtLocalPartition;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.apache.ignite.transactions.Transaction;

/**
 * Tests functionality related to {@link CacheState}.
 */
public abstract class CacheStateAbstractTest extends GridCommonAbstractTest {

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String gridName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(gridName);

        cfg.setCacheConfiguration(cacheConfiguration(null));

        return cfg;
    }

    /**
     * @param cacheName Cache name.
     * @return Cache configuration.
     */
    protected abstract CacheConfiguration cacheConfiguration(String cacheName);

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        G.stopAll(true);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        G.stopAll(true);
    }

    /**
     * Tests that state changes are propagated to existing and new nodes.
     * @throws Exception If fails.
     */
    public void testStatePropagation() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active();
        assert cache2.active();

        cache1.active(false);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));

        final IgniteCache cache3 = ignite3.cache(null);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active() && !cache3.active();
            }
        }, 5000);

        cache3.active(true);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache1.active() && cache2.active() && cache3.active();
            }
        }, 5000);
    }

    /**
     * Tests that state doesn't change until all aquired locks are released.
     * @throws Exception If fails.
     */
    public void testDeactivationWithPendingLock() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active() && cache2.active();

        Lock lock = cache1.lock(1);

        lock.lock();

        try {
            IgniteInternalFuture<?> fut = multithreadedAsync(new Runnable() {
                @Override public void run() {
                    cache1.active(false).get();
                }
            }, 1);

            U.sleep(5000);

            assert !fut.isDone();
            assert cache1.active() && cache2.active();
        }
        finally {
            lock.unlock();
        }

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        boolean ex = false;

        try {
            cache1.lock(2).lock();
        }
        catch (CacheException e) {
            ex = true;
            assert e.getMessage().equals("Failed to perform cache operation (cache state is not valid): null");
        }

        assert ex;
    }

    /**
     * Tests that state doesn't change until all pending transactions are finished.
     * @throws Exception If fails.
     */
    public void testDeactivationWithPendingTransaction() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active() && cache2.active();

        Transaction tx = ignite1.transactions().txStart();

        cache1.put(1, 1);

        IgniteInternalFuture<?> fut = multithreadedAsync(new Runnable() {
            @Override public void run() {
                cache1.active(false).get();
            }
        }, 1);

        U.sleep(5000);

        assert !fut.isDone();
        assert cache1.active() && cache2.active();

        cache1.put(2, 2);

        tx.commit();

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        boolean ex = false;

        try {
            cache1.put(3, 3);
        }
        catch (CacheException e) {
            ex = true;
        }

        assert ex;

        cache1.active(true);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache1.active() && cache2.active();
            }
        }, 5000);

        assert cache1.get(1).equals(1);
        assert cache1.get(2).equals(2);
    }

    /**
     * Tests that rebalancing is disabled when cache is inactive.
     * @throws Exception If fails.
     */
    public void testNoRebalancingWhenInactive() throws Exception {
        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active();
        assert cache2.active();

        cache1.put(1, 1);
        cache1.put(2, 2);

        cache1.active(false);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active();
            }
        }, 5000);

        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));

        final IgniteCache cache3 = ignite3.cache(null);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache1.active() && !cache2.active() && !cache3.active();
            }
        }, 5000);

        ignite1.close();
        ignite2.close();

        cache3.active(true);

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache3.active();
            }
        }, 5000);

        assert !cache3.containsKey(1);
        assert !cache3.containsKey(2);
    }

    /**
     * Test that during rebalancing all nodes receive partition with the greatest counter value.
     * @throws Exception If fails.
     */
    public void testRebalancingUsingCounters() throws Exception {
        if (cacheConfiguration(null).getCacheMode() != CacheMode.REPLICATED)
            return;

        IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));

        final IgniteCache cache1 = ignite1.cache(null);

        cache1.active(false).get();

        assert !cache1.active();

        IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));
        IgniteEx ignite3 = (IgniteEx)G.start(getConfiguration("test3"));

        final IgniteCache cache2 = ignite2.cache(null);
        final IgniteCache cache3 = ignite3.cache(null);

        assert !cache1.active() && !cache2.active() && !cache3.active();

        GridDhtLocalPartition part1 = ignite1.cachex().context().topology().localPartition(0, true);
        GridDhtLocalPartition part2 = ignite2.cachex().context().topology().localPartition(0, true);
        GridDhtLocalPartition part3 = ignite3.cachex().context().topology().localPartition(0, true);

        part1.updateCounter(10);
        part2.updateCounter(20);
        part3.updateCounter(5);

        cache1.active(true).get();

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache1.active() && cache2.active() && cache3.active();
            }
        }, 5000);

        assert ignite1.cachex().context().topology().localPartition(0, true).updateCounter() == 20;
        assert ignite2.cachex().context().topology().localPartition(0, true).updateCounter() == 20;
        assert ignite3.cachex().context().topology().localPartition(0, true).updateCounter() == 20;
    }

    /**
     * Test lost partitions.
     * @throws Exception If fails.
     */
    public void testLostPartitions() throws Exception {
        if (cacheConfiguration(null).getBackups() != 0 || cacheConfiguration(null).getCacheMode() != CacheMode.PARTITIONED)
            return;

        final IgniteEx ignite1 = (IgniteEx)G.start(getConfiguration("test1"));
        final IgniteEx ignite2 = (IgniteEx)G.start(getConfiguration("test2"));

        final IgniteCache cache1 = ignite1.cache(null);
        final IgniteCache cache2 = ignite2.cache(null);

        assert cache1.active();
        assert cache2.active();

        Set<Integer> ignite2Parts = new HashSet<>();

        for (int p : ignite2.affinity(null).allPartitions(ignite2.localNode())) {
            ignite2Parts.add(p);
        }

        int part = -1;

        for (int p : ignite1.affinity(null).allPartitions(ignite1.localNode())) {
            if (!ignite2Parts.contains(p)) {
                part = p;
                break;
            }
        }

        final int partition = part;

        cache1.put(partition, 1);
        assert cache2.get(partition).equals(1);

        ignite1.close();

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return cache2.lostPartitions().contains(partition);
            }
        }, 5000);

        boolean ex = false;

        try {
            cache2.get(partition);
        }
        catch (CacheException e) {
            ex = true;
        }

        assert ex;

        cache2.recoverPartitions(Collections.singleton(partition));

        assert GridTestUtils.waitForCondition(new GridAbsPredicate() {
            @Override public boolean apply() {
                return !cache2.lostPartitions().contains(partition);
            }
        }, 5000);

        cache2.put(partition, 2);

        assert cache2.get(partition).equals(2);
    }
}
