/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;


import io.netty.buffer.PoolArena.SizeClass;
import io.netty.util.Recycler;
import io.netty.util.Recycler.Handle;
import io.netty.util.internal.MathUtil;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.nio.ByteBuffer;
import java.util.Queue;

/**
 * Acts a Thread cache for allocations. This implementation is moduled after
 * <a href="http://people.freebsd.org/~jasone/jemalloc/bsdcan2006/jemalloc.pdf">jemalloc</a> and the descripted
 * technics of
 * <a href="https://www.facebook.com/notes/facebook-engineering/scalable-memory-allocation-using-jemalloc/480222803919">
 * Scalable memory allocation using jemalloc</a>.
 */
/*
虽然提供了多个PoolArena减少线程间的竞争，但是难免还是会存在锁竞争，所以需要利用ThreaLocal进一步优化，
把已申请的内存放入到ThreaLocal自然就没有竞争了。大体思路是在ThreadLocal里面放一个PoolThreadCache对象，
然后 todo 释放的内存都放入到PoolThreadCache里面，下次申请先从PoolThreadCache获取。
但是，如果thread1申请了一块内存，然后传到thread2在线程释放，这个Netty在内存holder对象里面会引用PoolThreadCache，所以还是会释放到thread1里
 */
final class PoolThreadCache {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(PoolThreadCache.class);

    final PoolArena<byte[]> heapArena;
    final PoolArena<ByteBuffer> directArena;

    // Hold the caches for the different size classes, which are tiny, small and normal.
    private final MemoryRegionCache<byte[]>[] tinySubPageHeapCaches;
    private final MemoryRegionCache<byte[]>[] smallSubPageHeapCaches;
    //其实就是 对应PoolArena中的tinySubpagePools数组，数组大小也是从 PoolArena中获取的
    // 存储tiny类型的内存缓存，该数组长度为32，其中只有下标为1~31的元素缓存了有效数据，第0号位空置。
// 这里内存大小的存储方式也与PoolSubpage类似，数组的每一号元素都存储了不同等级的内存块，每个等级的
// 内存块的内存大小差值为16byte，比如第1号位维护了大小为16byte的内存块，第二号为维护了大小为32byte的
// 内存块，依次类推，第31号位维护了大小为496byte的内存块。（索引从0开始）
    private final MemoryRegionCache<ByteBuffer>[] tinySubPageDirectCaches;
    //其实就是 对应PoolArena中的smallSubpagePools数组，数组大小也是从 PoolArena中获取的
    // 存储small类型的内存缓存，该数组长度为4，数组中每个元素中维护的内存块大小也是成等级递增的，并且这里
// 的递增方式是按照2的指数次幂进行的，比如第0号为维护的是大小为512byte的内存块，第1号位维护的是大小为
// 1024byte的内存块，第2号位维护的是大小为2048byte的内存块，第3号位维护的是大小为4096byte的内存块
    private final MemoryRegionCache<ByteBuffer>[] smallSubPageDirectCaches;

    //第0个元素:1个页面,第一个元素: 2个页面；第二个元素: 4个页面(因为申请的时候会正则化，因此3个页面也会正则化到4个页面)；
    private final MemoryRegionCache<byte[]>[] normalHeapCaches;
    // 存储normal类型的内存缓存。需要注意的是，这里虽说是维护的normal类型的缓存，但是其只维护2<<13，2<<14
// 和2<<15三个大小的内存块，而该数组的大小也正好为3，因而这三个大小的内存块将被依次放置在该数组中。
// 如果申请的目标内存大于2<<15，那么Netty会将申请动作交由PoolArena进行。
    private final MemoryRegionCache<ByteBuffer>[] normalDirectCaches;

    // Used for bitshifting when calculate the index of normal caches later
    // log2(页大小),默认13，用于计算normal的索引
    private final int numShiftsNormalDirect;
    // log2(页大小),默认13，用于计算normal的索引
    private final int numShiftsNormalHeap;
    //allocations达到 freeSweepAllocationThreshold会对内存块进行一次trim()操作，对使用较少的内存块，将其返还给PoolArena，以供给其他线程使用
    private final int freeSweepAllocationThreshold;

    //从本cache中分配内存的次数(包括成功和失败的)，达到阈值后会对内存块进行一次trim()操作，
    private int allocations;

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    PoolThreadCache(PoolArena<byte[]> heapArena, PoolArena<ByteBuffer> directArena,
                    int tinyCacheSize, int smallCacheSize, int normalCacheSize,
                    int maxCachedBufferCapacity, int freeSweepAllocationThreshold) {
        if (maxCachedBufferCapacity < 0) {
            throw new IllegalArgumentException("maxCachedBufferCapacity: "
                    + maxCachedBufferCapacity + " (expected: >= 0)");
        }
        this.freeSweepAllocationThreshold = freeSweepAllocationThreshold;
        this.heapArena = heapArena;
        this.directArena = directArena;
        if (directArena != null) {
            tinySubPageDirectCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageDirectCaches = createSubPageCaches(
                    smallCacheSize, directArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalDirect = log2(directArena.pageSize);
            normalDirectCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, directArena);

            directArena.numThreadCaches.getAndIncrement();
        } else {
            // No directArea is configured so just null out all caches
            tinySubPageDirectCaches = null;
            smallSubPageDirectCaches = null;
            normalDirectCaches = null;
            numShiftsNormalDirect = -1;
        }
        if (heapArena != null) {
            // Create the caches for the heap allocations
            tinySubPageHeapCaches = createSubPageCaches(
                    tinyCacheSize, PoolArena.numTinySubpagePools, SizeClass.Tiny);
            smallSubPageHeapCaches = createSubPageCaches(
                    smallCacheSize, heapArena.numSmallSubpagePools, SizeClass.Small);

            numShiftsNormalHeap = log2(heapArena.pageSize);
            normalHeapCaches = createNormalCaches(
                    normalCacheSize, maxCachedBufferCapacity, heapArena);

            heapArena.numThreadCaches.getAndIncrement();
        } else {
            // No heapArea is configured so just null out all caches
            tinySubPageHeapCaches = null;
            smallSubPageHeapCaches = null;
            normalHeapCaches = null;
            numShiftsNormalHeap = -1;
        }

        // Only check if there are caches in use.
        if ((tinySubPageDirectCaches != null || smallSubPageDirectCaches != null || normalDirectCaches != null
                || tinySubPageHeapCaches != null || smallSubPageHeapCaches != null || normalHeapCaches != null)
                && freeSweepAllocationThreshold < 1) {
            throw new IllegalArgumentException("freeSweepAllocationThreshold: "
                    + freeSweepAllocationThreshold + " (expected: > 0)");
        }
    }

    /**
     *
     * @param cacheSize 每种 缓存块的数量
     * @param numCaches 有多少种缓存块
     * @param sizeClass
     * @param <T>
     * @return
     */
    private static <T> MemoryRegionCache<T>[] createSubPageCaches(
            int cacheSize, int numCaches, SizeClass sizeClass) {
        if (cacheSize > 0 && numCaches > 0) {
            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[numCaches];
            for (int i = 0; i < cache.length; i++) {
                // TODO: maybe use cacheSize / cache.length
                cache[i] = new SubPageMemoryRegionCache<T>(cacheSize, sizeClass);
            }
            return cache;
        } else {
            return null;
        }
    }

    private static <T> MemoryRegionCache<T>[] createNormalCaches(
            int cacheSize, int maxCachedBufferCapacity, PoolArena<T> area) {
        if (cacheSize > 0 && maxCachedBufferCapacity > 0) {
            int max = Math.min(area.chunkSize, maxCachedBufferCapacity);
            int arraySize = Math.max(1, log2(max / area.pageSize) + 1);

            @SuppressWarnings("unchecked")
            MemoryRegionCache<T>[] cache = new MemoryRegionCache[arraySize];
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new NormalMemoryRegionCache<T>(cacheSize);
            }
            return cache;
        } else {
            return null;
        }
    }

    private static int log2(int val) {
        int res = 0;
        while (val > 1) {
            val >>= 1;
            res++;
        }
        return res;
    }

    /**
     * Try to allocate a tiny buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    // 申请tiny类型的内存块
    boolean allocateTiny(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForTiny(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    // 申请small类型的内存块
    boolean allocateSmall(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForSmall(area, normCapacity), buf, reqCapacity);
    }

    /**
     * Try to allocate a small buffer out of the cache. Returns {@code true} if successful {@code false} otherwise
     */
    // 申请normal类型的内存块
    boolean allocateNormal(PoolArena<?> area, PooledByteBuf<?> buf, int reqCapacity, int normCapacity) {
        return allocate(cacheForNormal(area, normCapacity), buf, reqCapacity);
    }

    // 从MemoryRegionCache中申请内存
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private boolean allocate(MemoryRegionCache<?> cache, PooledByteBuf buf, int reqCapacity) {
        if (cache == null) {
            // no cache found so just return false here
            return false;
        }
        // 从MemoryRegionCache中申请内存，本质上就是从其队列中申请，如果存在，则初始化申请到的内存块
        boolean allocated = cache.allocate(buf, reqCapacity);
        // 这里是如果当前PoolThreadCache中申请内存的次数达到了8192次，则对内存块进行一次trim()操作，
        // 对使用较少的内存块，将其返还给PoolArena，以供给其他线程使用
        if (++ allocations >= freeSweepAllocationThreshold) {
            //重新计数
            allocations = 0;
            trim();
        }
        return allocated;
    }

    /**
     * Add {@link PoolChunk} and {@code handle} to the cache if there is enough room.
     * Returns {@code true} if it fit into the cache {@code false} otherwise.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    boolean add(PoolArena<?> area, PoolChunk chunk, long handle, int normCapacity, SizeClass sizeClass) {
        // 在缓存数组中找到符合的元素
        MemoryRegionCache<?> cache = cache(area, normCapacity, sizeClass);
        //如果没有对应档位的缓存，直接返回
        if (cache == null) {
            return false;
        }
        return cache.add(chunk, handle);
    }

    private MemoryRegionCache<?> cache(PoolArena<?> area, int normCapacity, SizeClass sizeClass) {
        switch (sizeClass) {
        case Normal:
            return cacheForNormal(area, normCapacity);
        case Small:
            return cacheForSmall(area, normCapacity);
        case Tiny:
            return cacheForTiny(area, normCapacity);
        default:
            throw new Error();
        }
    }

    /**
     *  Should be called if the Thread that uses this cache is about to exist to release resources out of the cache
     */
    void free() {
        int numFreed = free(tinySubPageDirectCaches) +
                free(smallSubPageDirectCaches) +
                free(normalDirectCaches) +
                free(tinySubPageHeapCaches) +
                free(smallSubPageHeapCaches) +
                free(normalHeapCaches);

        if (numFreed > 0 && logger.isDebugEnabled()) {
            logger.debug("Freed {} thread-local buffer(s) from thread: {}", numFreed, Thread.currentThread().getName());
        }

        //减少 directArena对应PoolArena的计数
        if (directArena != null) {
            directArena.numThreadCaches.getAndDecrement();
        }
        //减少 heapArena对应PoolArena的计数
        if (heapArena != null) {
            heapArena.numThreadCaches.getAndDecrement();
        }
    }

    private static int free(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return 0;
        }

        int numFreed = 0;
        //释放需要遍历Cache数组，对每一个MemoryRegionCache执行free()
        for (MemoryRegionCache<?> c: caches) {
            numFreed += free(c);
        }
        return numFreed;
    }

    private static int free(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return 0;
        }
        return cache.free();
    }

    void trim() {
        //对所有内存块进行trim操作
        trim(tinySubPageDirectCaches);
        trim(smallSubPageDirectCaches);
        trim(normalDirectCaches);
        trim(tinySubPageHeapCaches);
        trim(smallSubPageHeapCaches);
        trim(normalHeapCaches);
    }

    private static void trim(MemoryRegionCache<?>[] caches) {
        if (caches == null) {
            return;
        }
        for (MemoryRegionCache<?> c: caches) {
            //对所有链表进行trim操作
            trim(c);
        }
    }

    private static void trim(MemoryRegionCache<?> cache) {
        if (cache == null) {
            return;
        }
        cache.trim();
    }

    private MemoryRegionCache<?> cacheForTiny(PoolArena<?> area, int normCapacity) {
        // 计算当前数组下标索引，由于tiny类型的内存块最小为0byte，每一层级相差16byte，因而这里的计算方式就是
        // 将目标内存大小除以16
        int idx = PoolArena.tinyIdx(normCapacity);
        if (area.isDirect()) {
            // 返回tiny类型的数组中对应位置的MemoryRegionCache
            return cache(tinySubPageDirectCaches, idx);
        }
        return cache(tinySubPageHeapCaches, idx);
    }

    private MemoryRegionCache<?> cacheForSmall(PoolArena<?> area, int normCapacity) {
        // 计算当前数组下标的索引，由于small类型的内存块大小都是2的指数次幂，因而这里就是将目标内存大小
        // 除以1024之后计算其偏移量
        int idx = PoolArena.smallIdx(normCapacity);
        // 返回small类型的数组中对应位置的MemoryRegionCache
        if (area.isDirect()) {
            return cache(smallSubPageDirectCaches, idx);
        }
        return cache(smallSubPageHeapCaches, idx);
    }

    //log2( 正则化后需要分配的内存块大小 >> log2(pageSize)),默认:第0个元素:1个页面,第一个元素: 2个页面；第二个元素: 4个页面(因为申请的时候会正则化，因此3个页面也会正则化到4个页面)；
    private MemoryRegionCache<?> cacheForNormal(PoolArena<?> area, int normCapacity) {
        if (area.isDirect()) {
            int idx = log2(normCapacity >> numShiftsNormalDirect);
            return cache(normalDirectCaches, idx);
        }
        int idx = log2(normCapacity >> numShiftsNormalHeap);
        return cache(normalHeapCaches, idx);
    }

    private static <T> MemoryRegionCache<T> cache(MemoryRegionCache<T>[] cache, int idx) {
        if (cache == null || idx > cache.length - 1) {
            return null;
        }
        return cache[idx];
    }

    /**
     * Cache used for buffers which are backed by TINY or SMALL size.
     */
    private static final class SubPageMemoryRegionCache<T> extends MemoryRegionCache<T> {
        SubPageMemoryRegionCache(int size, SizeClass sizeClass) {
            super(size, sizeClass);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBufWithSubpage(buf, handle, reqCapacity);
        }
    }

    /**
     * Cache used for buffers which are backed by NORMAL size.
     */
    private static final class NormalMemoryRegionCache<T> extends MemoryRegionCache<T> {
        NormalMemoryRegionCache(int size) {
            super(size, SizeClass.Normal);
        }

        @Override
        protected void initBuf(
                PoolChunk<T> chunk, long handle, PooledByteBuf<T> buf, int reqCapacity) {
            chunk.initBuf(buf, handle, reqCapacity);
        }
    }

    private abstract static class MemoryRegionCache<T> {
        private final int size; // 队列长度
        private final Queue<Entry<T>> queue; // 队列
        private final SizeClass sizeClass; // Tiny/Small/Normal
        //allocations表示当前MemoryRegionCache距离上一次trim操作
        // 的已经分配出去的元素数量， 用于计算需要trim的内存。 allocations不是线程安全的，但是只有一个线程调用的话就没有问题
        private int allocations;

        /**
         *  @param size 链表长度
         * @param size
         * @param sizeClass
         */
        MemoryRegionCache(int size, SizeClass sizeClass) {
            this.size = MathUtil.safeFindNextPositivePowerOfTwo(size);
            //MPSC（Multiple Producer Single Consumer）队列即多个生产者单一消费者队列
            //ByteBuf的分配和释放可能在不同的线程中，这里的多生产者即多个不同的释放线程，这样才能保证多个释放线程同时释放ByteBuf时所占空间正确添加到队列中。
            queue = PlatformDependent.newFixedMpscQueue(this.size);
            this.sizeClass = sizeClass;
        }

        /**
         * Init the {@link PooledByteBuf} using the provided chunk and handle with the capacity restrictions.
         */
        protected abstract void initBuf(PoolChunk<T> chunk, long handle,
                                        PooledByteBuf<T> buf, int reqCapacity);

        /**
         * Add to cache if not already full.
         */
        @SuppressWarnings("unchecked")
        public final boolean add(PoolChunk<T> chunk, long handle) {
            //新建Entry
            Entry<T> entry = newEntry(chunk, handle);
            boolean queued = queue.offer(entry);
            if (!queued) {
                // 队列已满，不缓存，立即回收entry对象
                // If it was not possible to cache the chunk, immediately recycle the entry
                entry.recycle();
            }

            return queued;
        }

        /**
         * Allocate something out of the cache if possible and remove the entry from the cache.
         */
        public final boolean allocate(PooledByteBuf<T> buf, int reqCapacity) {
            // 尝试从队列中获取，如果队列中不存在，说明没有对应的内存块，则返回false，表示申请失败
            Entry<T> entry = queue.poll();
            if (entry == null) {
                return false;
            }
            // 走到这里说明队列中存在对应的内存块，那么通过其存储的Entry对象来初始化ByteBuf对象，
            // 如此即表示申请内存成功
            initBuf(entry.chunk, entry.handle, buf, reqCapacity);
            // 对entry对象进行循环利用
            entry.recycle();

            // 更新当前已经申请的内存数量
            // allocations is not thread-safe which is fine as this is only called from the same thread all time.
            ++ allocations;
            return true;
        }

        /**
         * Clear out this cache and free up all previous cached {@link PoolChunk}s and {@code handle}s.
         */
        //清除所有节点
        public final int free() {
            return free(Integer.MAX_VALUE);
        }

        private int free(int max) {
            int numFreed = 0;
            // 依次从队列中取出Entry数据，调用freeEntry()方法释放该Entry
            for (; numFreed < max; numFreed++) {
                Entry<T> entry = queue.poll();
                if (entry != null) {
                    // 通过当前Entry中保存的PoolChunk和handle等数据释放当前内存块
                    freeEntry(entry);
                } else {
                    // all cleared
                    return numFreed;
                }
            }
            return numFreed;
        }

        /**
         * Free up cached {@link PoolChunk}s if not allocated frequently enough.
         */
        //todo 原因：期望一个MemoryRegionCache频繁进行回收-分配，这样allocations > size，将不会释放队列中的任何一个节点表示的内存空间；但如果长时间没有分配，则应该释放这一部分空间，防止内存占据过多。Tiny请求缓存512个节点，由此可知当使用率超过 512 / 8192 = 6.25% 时就不会释放节点。
        //如果链表从上一次开始分配了n次，链表的长度为size，那么就会释放size-n块内存块回PoolArena中，如果没有足够的内存块，则释放全部内存块
        public final void trim() {
            // size表示当前MemoryRegionCache中队列的最大程度，allocations表示当前MemoryRegionCache距离上一次trim操作
            // 的已经分配出去的元素数量
            int free = size - allocations;
            allocations = 0;

            // We not even allocated all the number that are

            //// 如果申请的次数连队列的容量都没达到，则释放该内存块
            if (free > 0) {
                free(free);
            }
        }

        // 通过当前Entry中保存的PoolChunk和handle等数据释放当前内存块
        @SuppressWarnings({ "unchecked", "rawtypes" })
        private  void freeEntry(Entry entry) {
            PoolChunk chunk = entry.chunk;
            long handle = entry.handle;

            // recycle now so PoolChunk can be GC'ed.
            entry.recycle();

            chunk.arena.freeChunk(chunk, handle, sizeClass);
        }

        static final class Entry<T> {
            // 当前Entry对象对应的对象池中的handle
            final Handle<Entry<?>> recyclerHandle;
            // 记录了当前内存块是从哪一个PoolChunk中申请得来的
            //当前内存块所在的PoolChunk对象
            PoolChunk<T> chunk;
            //记录了当前内存块是在PoolChunk和PoolSubpage中的哪个位置
            // 由于当前申请的内存块在PoolChunk以及PoolSubpage中的位置是可以通过一个长整型参数来表示的，
            // 这个长整型参数就是这里的handle，因而这里直接将其记录下来，以便后续需要将当前内存块释放到
            // PoolArena中时，能够快速获取其所在的位置
            //这个参数是从PoolChunk和PoolSubpage申请的时候返回的
            long handle = -1;

            Entry(Handle<Entry<?>> recyclerHandle) {
                this.recyclerHandle = recyclerHandle;
            }

            void recycle() {
                chunk = null;
                handle = -1;
                recyclerHandle.recycle(this);
            }
        }

        @SuppressWarnings("rawtypes")
        private static Entry newEntry(PoolChunk<?> chunk, long handle) {
            Entry entry = RECYCLER.get();
            entry.chunk = chunk;
            entry.handle = handle;
            return entry;
        }

        @SuppressWarnings("rawtypes")
        private static final Recycler<Entry> RECYCLER = new Recycler<Entry>() {
            @SuppressWarnings("unchecked")
            @Override
            protected Entry newObject(Handle<Entry> handle) {
                return new Entry(handle);
            }
        };
    }
}
