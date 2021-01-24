/*
 * Copyright 2013 The Netty Project
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

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectCleaner;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;
/*
Recycler用来实现对象池，其中对应堆内存和直接内存的池化实现分别是PooledHeapByteBuf和PooledDirectByteBuf。
 */
/**
 * Light-weight object pool based on a thread-local stack.
 *
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    /**
     * 唯一ID生成器
     * 用在两处：
     * 1、当前线程ID
     * 2、WeakOrderQueue的id
     */
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    /**
     * 当前recycler唯一id，static变量, 生成并获取一个唯一id.
     * 用于pushNow()中的item.recycleId和item.lastRecycleId的设定
     */
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    /**
     * 每个Stack默认的最大容量
     * 注意：
     * 1、当io.netty.recycler.maxCapacityPerThread<=0时，禁用回收功能（在netty中，只有=0可以禁用，<0默认使用4k）
     * 2、Recycler中有且只有两个地方存储DefaultHandle对象（Stack和Link），
     * 最多可存储MAX_CAPACITY_PER_THREAD + 最大可共享容量 = 4k + 4k/2 = 6k
     *
     * 实际上，在netty中，Recycler提供了两种设置属性的方式
     * 第一种：-Dio.netty.recycler.ratio等jvm启动参数方式
     * 第二种：Recycler(int maxCapacityPerThread)构造器传入方式
     */
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.

    /**
     * 每个stack的默认容量
     */
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    /**
     * 每个Stack默认的初始容量，默认为256和DEFAULT_MAX_CAPACITY_PER_THREAD的最小值
     * 后续根据需要进行扩容，直到<=MAX_CAPACITY_PER_THREAD
     */
    private static final int INITIAL_CAPACITY;
    /**
     * 最大可共享的容量因子。
     * 最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
     */
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    /**
     * 每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
     * 实际上就是当前线程的Map<Stack<?>, WeakOrderQueue>的size最大值
     */
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    /**
     * WeakOrderQueue中的每个Link中的数组DefaultHandle<?>[] elements容量，默认为16，
     * 当一个Link中的DefaultHandle元素达到16个时，会新创建一个Link进行存储，这些Link组成链表，当然
     * 所有的Link加起来的容量要<=最大可共享容量。
     */
    private static final int LINK_CAPACITY;
    /**
     * 回收因子，默认为8。
     * 即默认每8个对象，允许回收一次，直接扔掉7个，可以让recycler的容量缓慢的增大，避免爆发式的请求
     */
    private static final int RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = safeFindNextPositivePowerOfTwo(SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    //每个stack能存储多少个元素
    private final int maxCapacityPerThread;
    //最大可共享的容量因子，最大可共享的容量 = maxCapacity / maxSharedCapacityFactor，maxSharedCapacityFactor默认为2
    private final int maxSharedCapacityFactor;
    //回收因子的 mask
    private final int ratioMask;
    //每个线程可拥有多少个WeakOrderQueue，默认为2*cpu核数
    private final int maxDelayedQueuesPerThread;

    /**
     * 1、每个Recycler对象都有一个threadLocal
     * 原因：因为一个Stack要指明存储的对象泛型T，而不同的Recycler<T>对象的T可能不同，
     * 所以此处的FastThreadLocal是对象级别
     * 2、每条线程都有一个Stack<T>对象
     */
    //存放本线程自己分配回收的对象
    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    ratioMask, maxDelayedQueuesPerThread);
        }

        @Override
        protected void onRemoval(Stack<T> value) {
            //如果可以安全地删除一些开销，让我们直接从WeakHashMap中删除WeakOrderQueue
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        ratioMask = safeFindNextPositivePowerOfTwo(ratio) - 1;
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    //同步获取对象
    @SuppressWarnings("unchecked")
    public final T get() {
        //如果maxCapacityPerThread == 0，禁止回收功能
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        /**
         * 1、获取当前线程的Stack<T>对象,从stack中pop出1个DefaultHandle，返回该DefaultHandle的真正对象。
         */
        Stack<T> stack = threadLocal.get();
        /**
         * 2、从stack中pop出1个DefaultHandle，返回该DefaultHandle的真正对象。
         */
        DefaultHandle<T> handle = stack.pop();
        if (handle == null) {
            /**
             * 3、 新建一个DefaultHandle对象 -> 然后新建T对象 -> 存储DefaultHandle对象到T对象中
             * 此处会发现一个DefaultHandle对象对应一个Object对象，Object对象包含 DefaultHandle对象，二者相互包含。
             */
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        /**
         * 4、返回value
         */
        return (T) handle.value;
    }

    //回收一个对象，T为对象泛型
    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        //调用该对象DefaultHandle.recycle()方法
        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**当没有可用对象时创建对象的实现方法
     * 创建一个对象
     * 1、由子类进行复写，所以使用protected修饰
     * 2、传入Handle对象，对创建出来的对象进行回收操作
     */
    protected abstract T newObject(Handle<T> handle);

    public static void main(String[] args) {

    }

    /**
     * 提供对象的回收功能，由子类进行复写
     * 目前该接口只有两个实现：NOOP_HANDLE和DefaultHandle
     */
    public interface Handle<T> {
        void recycle(T object);
    }

    //对象的包装类，在Recycler中缓存的对象都会包装成DefaultHandle类。
    static final class DefaultHandle<T> implements Handle<T> {
        // (item.recycleId | item.lastRecycleId) != 0 等价于 item.recycleId!=0 && item.lastRecycleId!=0
        // 当item开始创建时item.recycleId==0 && item.lastRecycleId==0
        // 当item被recycle时，item.recycleId==x，item.lastRecycleId==y 进行赋值
        // 当item被poll之后， item.recycleId = item.lastRecycleId = 0
        // 所以当item.recycleId 和 item.lastRecycleId 任何一个不为0，则表示已经被回收了
        /**
         * 1.在同线程回收中会设置值OWN_THREAD_ID；
         * 2.异线程回收时设置为 当前的WeakOrderQueue的唯一ID
         * 3. 在从对象池中取出时置位0
         */
        //和lastRecycledId区分的目的好像只是为了标识是同线程回收还是异线程回收
        //从对象池中取出被使用时会被设置为0
        private int lastRecycledId;
        /**
         * 1.在同线程回收中会设置值OWN_THREAD_ID(包括transfer到stack中)
         * 2.在从对象池中取出时置位0
         */
        //从对象池中取出被使用时会被设置为0
        private int recycleId;

        /**
         * 标记是否已经被回收(而不是直接被丢弃)(只有同线程回收才会被设置)：
         * 该值仅仅用于控制是否执行 (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，
         * 重复回收的操作由item.recycleId | item.lastRecycledId来阻止
         */
        boolean hasBeenRecycled;

        /**
         * 创建 Handle的stack
         */
        private Stack<?> stack;
        /**
         * 真正的对象，value与Handle一一对应
         */
        private Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            // 防护性判断
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }
            /**
             * 回收对象，this指的是当前的DefaultHandle对象
             */
            stack.push(this);
        }
    }

    /**
     * 1、每个Recycler类（而不是每一个Recycler对象）都有一个DELAYED_RECYCLED
     * 原因：可以根据一个Stack<T>对象唯一的找到一个WeakOrderQueue对象，所以此处不需要每个对象建立一个DELAYED_RECYCLED
     * 2、由于DELAYED_RECYCLED是一个类变量，所以需要包容多个T，此处泛型需要使用?
     * 3、WeakHashMap：当Stack没有强引用可达时，整个Entry{Stack<?>, WeakOrderQueue}都会加入相应的弱引用队列等待回收
     */
    //存放的是其他线程分配本线程回收的对象
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    //存储其它线程回收到本线程stack的对象，当某个线程从Stack中获取不到对象时会从WeakOrderQueue中获取对象。
    // 每个线程的Stack拥有1个WeakOrderQueue链表，链表每个节点对应1个其它线程的WeakOrderQueue，
    // 其它线程回收到该Stack的对象就存储在这个WeakOrderQueue里。
    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue {

        /**
         * 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues，
         * 对于后续的Stack，其对应的WeakOrderQueue设置为DUMMY，
         * 后续如果检测到DELAYED_RECYCLED中对应的Stack的value是WeakOrderQueue.DUMMY时，直接返回，不做存储操作
         */
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        //WeakOrderQueue中包含1个Link链表，回收对象存储在链表某个Link节点里，
        // 当Link节点存储的回收对象满了时会新建1个Link放在Link链表尾。
        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        //继承了 AtomicInteger，value为当前Link中已经存放了多少元素
        @SuppressWarnings("serial")
        static final class Link extends AtomicInteger {
            //对象池中的元素
            private final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            //读索引，transfer操作当前已经操作到本Link的第几个元素，如果为LINK_CAPACITY表示本Link中的数据已经被transfer完了
            private int readIndex;
            /**
             * Link的下一个节点
             */
            Link next;
        }

        /*
        这作为头部链接的占位符，但也会被ObjectCleaner用来返回之前保留的空间。
        它不包含对Stack或WeakOrderQueue的引用任何Stack或者WeakOrderQueue的引用。
         */
        // This act as a place holder for the head Link but also will be used by the ObjectCleaner
        // to return space that was before reserved. Its important this does not hold any reference to
        // either Stack or WeakOrderQueue.
        //todo 只有对应的WeakOrderQueue被gc时，才会运行本Head对象
        static final class Head implements Runnable {
            private final AtomicInteger availableSharedCapacity;

            //head对象对应的WeakOrderQueue的link链表第一个有效的link节点
            /**
             * eg. Head -> Link1 -> Link2
             * 假设此时的读操作在Link2上进行时，则此处的link == Link2，见transfer(Stack dst),
             * 实际上此时Link1已经被读完了，Link1变成了垃圾（一旦一个Link的读指针指向了最后，则该Link不会被重复利用，而是被GC掉，
             * 之后回收空间，新建Link再进行操作）
             */
            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            //回收对应WeakOrderedQueue的所有link节点
            @Override
            public void run() {
                Link head = link;
                while (head != null) {
                    //回收一个 link的容量
                    reclaimSpace(LINK_CAPACITY);
                    head = link.next;
                }
            }

            //当link被转移时，回收容量
            void reclaimSpace(int space) {
                assert space >= 0;
                availableSharedCapacity.addAndGet(space);
            }

            /**
             * 这里可以理解为一次内存的批量分配，每次从availableSharedCapacity中分配space个大小的内存。
             * 如果一个Link中不是放置一个DefaultHandle[]，而是只放置一个DefaultHandle，那么此处的space==1，这样的话就需要频繁的进行内存分配
             */
            boolean reserveSpace(int space) {
                return reserveSpace(availableSharedCapacity, space);
            }

            //判断是否 还能从共享容量中分配
            static boolean reserveSpace(AtomicInteger availableSharedCapacity, int space) {
                assert space >= 0;
                for (;;) {
                    //获取 剩余可用容量
                    int available = availableSharedCapacity.get();
                    //如果小于space，则直接返回
                    if (available < space) {
                        return false;
                    }
                    //否则，从可用容量中减去 需要分配的部分
                    if (availableSharedCapacity.compareAndSet(available, available - space)) {
                        return true;
                    }
                }
            }
        }

        //Link链表的头节点
        // chain of data items
        private final Head head;
        //Link链表的尾节点
        private Link tail;
        //下一个 WeakOrderQueue节点
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        /**
         * 1、why WeakReference？与Stack相同。
         * 2、作用是在poll的时候，如果owner不存在了，则需要将该线程所包含的WeakOrderQueue的元素释放，然后从链表中删除该Queue。
         */
        //创建本WeakOrderQueue的线程，而不是stack对应的线程
        private final WeakReference<Thread> owner;
        //本WeakOrderQueue的唯一标记
        private final int id = ID_GENERATOR.getAndIncrement();

        private WeakOrderQueue() {
            owner = null;
            head = new Head(null);
        }

        /*
        WeakOrderQueue实现了多线程环境下回收对象的机制，当由其它线程回收对象到stack时
        会为该stack创建1个WeakOrderQueue，这些由其它线程创建的WeakOrderQueue会在该stack
        中按链表形式串联起来，每次创建1个WeakOrderQueue会把该WeakOrderQueue
        作为该stack的head WeakOrderQueue
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            tail = new Link();


            /*
            重要的是，我们不会将堆栈本身存储在WeakOrderQueue中，因为堆栈也在WeakHashMap中用作关键字。
            所以只需存储封装的AtomicInteger，它应该允许堆栈本身已经打开。
             */
            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            // 创建Link链表头节点，只是占位符
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            owner = new WeakReference<Thread>(thread);
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // 创建WeakOrderQueue
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            // 将该queue赋值给stack的head属性
            stack.setHead(queue);

            // We need to reclaim all space that was reserved by this WeakOrderQueue so we not run out of space in
            // the stack. This is needed as we not have a good life-time control over the queue as it is used in a
            // WeakHashMap which will drop it at any time.
            /**
             * 将新建的queue添加到Cleaner中，当queue不可达时，
             * 调用head中的run()方法回收内存availableSharedCapacity，否则该值将不会增加，影响后续的Link的创建
             */
            final Head head = queue.head;
            ObjectCleaner.register(queue, head);

            return queue;
        }

        private void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        /**
         *
         * @param stack
         * @param thread 创建queue的线程，而不是stack对应的线程
         * @return
         */
        static WeakOrderQueue allocate(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            /**
             * 如果该stack的可用共享空间(stack.availableSharedCapacity)还能再容下1个WeakOrderQueue（LINK_CAPACITY），那么创建1个WeakOrderQueue，否则返回null
             */
            //判断是否 还有 可用共享空间
            return Head.reserveSpace(stack.availableSharedCapacity, LINK_CAPACITY)
                    ? newQueue(stack, thread) : null;
        }

        void add(DefaultHandle<?> handle) {
            //设置lastRecycledId
            handle.lastRecycledId = id;

            Link tail = this.tail;
            int writeIndex;
            //判断最后一个Link是否已经到达LINK_CAPACITY容量
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //分配 LINK_CAPACITY 的容量
                if (!head.reserveSpace(LINK_CAPACITY)) {
                    //如果不能分配直接返回
                    // Drop it.
                    return;
                }
                /**
                 * 此处创建一个Link，会将该Link作为新的tail-Link，之前的tail-Link已经满了，成为正常的Link了。重组Link链表
                 * 之前是HEAD -> tail-Link，重组后HEAD -> 之前的tail-Link -> 新的tail-Link
                 */
                // 如果已经满了，创建一个新的Link对象，之后重组Link链表，然后添加元素的末尾的Link（除了这个Link，前边的Link全部已经满了）
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = new Link();

                writeIndex = tail.get();
            }
            //link对象如果没满，直接添加；
            tail.elements[writeIndex] = handle;
            /**
             * 如果使用者在将DefaultHandle对象压入队列后，
             * 将Stack设置为null，但是此处的DefaultHandle是持有stack的强引用的，则Stack对象无法回收；
             * 而且由于此处DefaultHandle是持有stack的强引用，WeakHashMap中对应stack的WeakOrderQueue也无法被回收掉了，导致内存泄漏。
             */
            handle.stack = null;
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            //tail本身继承于AtomicInteger，元素数量+1
            tail.lazySet(writeIndex + 1);
        }

        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        boolean transfer(Stack<?> dst) {
            // 寻找第一个Link（Head不是Link）
            Link head = this.head.link;
            // head == null，表示只有Head一个节点，没有存储数据的节点，直接返回
            if (head == null) {
                return false;
            }

            /**
             * 如果head Link的readIndex到达了Link的容量LINK_CAPACITY，说明该Link已经被scavengge完了。
             * 这时需要把下一个Link作为新的head Link。
             */
            // 如果第一个Link节点的readIndex索引已经到达该Link对象的DefaultHandle[]的尾部，
            // 则判断当前的Link节点的下一个节点是否为null，如果为null，说明已经达到了Link链表尾部，直接返回，
            // 否则，将当前的Link节点的下一个Link节点赋值给this.head.link，进而对下一个Link节点进行操作
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    return false;
                }
                this.head.link = head = head.next;
            }

            /**
             * head Link的回收对象数组的最大位置
             */
            // 获取Link节点的readIndex,即当前的Link节点的第一个有效元素的位置
            final int srcStart = head.readIndex;
            /**
             * head Link可以scavenge的DefaultHandle的数量
             */
            // 获取Link节点的writeIndex，即当前的Link节点的最后一个有效元素的位置
            int srcEnd = head.get();
            // 计算Link节点中可以被转移的元素个数
            final int srcSize = srcEnd - srcStart;
            if (srcSize == 0) {
                return false;
            }

            // 获取转移元素的目的地Stack中当前的元素个数
            final int dstSize = dst.size;
            /**
             * 每次会尽可能scavenge整个head Link，如果head Link的DefaultHandle数组能全部迁移到stack中，
             * stack的DefaultHandle数组预期容量
             */
            // 计算期盼的容量(如果head Link的DefaultHandle数组能全部迁移到stack中，stack的DefaultHandle数组预期容量)
            final int expectedCapacity = dstSize + srcSize;

            /**
             * 如果expectedCapacity大于目的地Stack的长度
             * 1、对目的地Stack进行扩容
             * 2、计算Link中最终的可转移的最后一个元素的下标
             */
            /**
             * 如果预期容量大于stack的DefaultHandle数组最大长度，说明本次无法
             * 将head Link的DefaultHandle数组全部迁移到stack中
             */
            if (expectedCapacity > dst.elements.length) {
                //将当前 元素数量扩容(每次扩容两倍，直到大于 最大容量或者大于预期容量)，取预期容量和 最大容量的最小值
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                //计算Link中最终的可转移的最后一个元素的下标
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            if (srcStart != srcEnd) {
                /**
                 * // 获取Link节点的DefaultHandle[]
                 */
                final DefaultHandle[] srcElems = head.elements;
                /**
                 * // 获取目的地Stack的DefaultHandle[]
                 */
                final DefaultHandle[] dstElems = dst.elements;
                // dst数组的大小，会随着元素的迁入而增加，如果最后发现没有增加，那么表示没有迁移成功任何一个元素
                int newDstSize = dstSize;
                /**
                 * 迁移head Link的DefaultHandle数组到stack的DefaultHandle数组
                 */
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle element = srcElems[i];
                    /**
                     * 设置element.recycleId 或者 进行防护性判断
                     */
                    if (element.recycleId == 0) {
                        //因为在异线程 回收时，只会设置 lastRecycledId；在同线程回收时会设置 recycleId，因此将 lastRecycledId赋值给 recycleId
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    // 置空Link节点的DefaultHandle[i]
                    srcElems[i] = null;

                    // 扔掉放弃7/8的元素 todo 转移的时候 也会有 8个中扔掉七个
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    // 将可转移成功的DefaultHandle元素的stack属性设置为目的地Stack
                    element.stack = dst;
                    // 将DefaultHandle元素转移到目的地Stack的DefaultHandle[newDstSize ++]中
                    dstElems[newDstSize ++] = element;
                }

                /**
                 * 当head节点的对象全都转移给stack后，取head下一个节点作为head，下次转移的时候再从新的head转移回收的对象
                 */
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // 将Head指向下一个Link，也就是将当前的Link给回收掉了
                    // 假设之前为Head -> Link1 -> Link2，回收之后为Head -> Link2
                    // Add capacity back as the Link is GCed.
                    this.head.reclaimSpace(LINK_CAPACITY);
                    this.head.link = head.next;
                }

                /**
                 * 迁移完成后更新原始head Link的readIndex
                 */
                // 重置readIndex
                head.readIndex = srcEnd;
                // 表示没有被回收任何一个对象，直接返回
                if (dst.size == newDstSize) {
                    return false;
                }
                // 将新的newDstSize赋值给目的地Stack的size
                dst.size = newDstSize;
                return true;
            } else {
                //如果目标stack已经满了
                // The destination stack is full already.
                return false;
            }
        }
    }

    //存储本线程回收的对象。对象的获取和回收对应Stack的pop和push，
    // 即获取对象时从Stack中pop出1个DefaultHandle，回收对象时将
    // 对象包装成DefaultHandle push到Stack中。Stack会与线程绑定，
    // 即每个用到Recycler的线程都会拥有1个Stack，
    // 在该线程中获取对象都是在该线程的Stack中pop出一个可用对象。
    static final class Stack<T> {

        /**
         * 该Stack所属的Recycler对象
         */
        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        /**
         * 该Stack所属的线程
         * why WeakReference?
         * 假设该线程对象在外界已经没有强引用了，那么实际上该线程对象就可以被回收了。但是如果此处用的是强引用，那么虽然外界不再对该线程有强引用，
         * 但是该stack对象还持有强引用（假设用户存储了DefaultHandle对象，然后一直不释放，而DefaultHandle对象又持有stack引用），导致该线程对象无法释放。
         *
         * from netty:
         * The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all
         * if the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear it in a timely manner)
         */
        final WeakReference<Thread> threadRef;
        /**
         * 最大可用的共享内存大小(被所有进程对应stack的WeakOrderQueue共用)，默认为maxCapacity/maxSharedCapacityFactor = 4k/2 = 2k = 2048
         * 假设当前的Stack是线程A的，则其他线程B~X等去回收线程A创建的对象时，可回收最多A创建的多少个对象
         * 注意：那么实际上线程A创建的对象最终最多可以被回收maxCapacity + availableSharedCapacity个，默认为6k个
         *
         * why AtomicInteger?
         * 当线程B和线程C同时创建线程A的WeakOrderQueue的时候，会同时分配内存，需要同时操作availableSharedCapacity
         * 具体见：WeakOrderQueue.allocate
         */
        final AtomicInteger availableSharedCapacity;
        /**
         * DELAYED_RECYCLED中最多可存储的{Stack，WeakOrderQueue}键值对个数
         */
        final int maxDelayedQueues;

        /**
         * elements最大的容量：默认最大为4k，4096
         */
        private final int maxCapacity;
        /**
         * 默认为8-1=7，即2^3-1，控制每8个元素只有一个可以被recycle，其余7个被扔掉，只有同线程 回收和transfer的时候才会使用
         */
        private final int ratioMask;
        /**
         * Stack底层数据结构，真正的用来存储数据
         */
        private DefaultHandle<?>[] elements;
        /**
         * elements中的元素个数，同时也可作为操作数组的下标
         * 数组只有elements.length来计算数组容量的函数，没有计算当前数组中的元素个数的函数，
         * 所以需要我们去记录，不然需要每次都去计算
         */
        private int size;
        /**
         * 每有一个元素将要被回收, 则该值+1，例如第一个被回收的元素的handleRecycleCount=handleRecycleCount+1=0
         * 与ratioMask配合，用来决定当前的元素是被回收还是被drop。
         * 例如 ++handleRecycleCount & ratioMask（7），其实相当于 ++handleRecycleCount % 8，
         * 则当 ++handleRecycleCount = 0/8/16/...时，元素被回收，其余的元素直接被drop
         */
        private int handleRecycleCount = -1; // Start with -1 so the first one will be recycled.
        //cursor: 当前被转移元素到stack中的WorkOrderQueue(只在转移时使用)，
        // prev:上一个转移元素到stack中的WorkOrderQueue(只在转移时使用)
        private WeakOrderQueue cursor, prev;
        /**
         * 该值是当线程B回收线程A创建的对象时，线程B会为线程A的Stack对象创建一个WeakOrderQueue对象，
         * 该WeakOrderQueue指向这里的head，用于后续线程A对对象的查找操作
         * Q: why volatile?
         * A: 假设线程A正要读取对象X，此时需要从其他线程的WeakOrderQueue中读取，假设此时线程B正好创建Queue，并向Queue中放入一个对象X；
         * 假设恰好次Queue就是线程A的Stack的head
         * 使用volatile可以立即读取到该queue。
         *
         * 对于head的设置，具有同步问题。具体见此处的volatile和synchronized void setHead(WeakOrderQueue queue)
         */
        //最新创建的 WeakOrderQueue,newHead.next=oldHead.next
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int ratioMask, int maxDelayedQueues) {
            this.parent = parent;
            threadRef = new WeakReference<Thread>(thread);
            this.maxCapacity = maxCapacity;
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            this.ratioMask = ratioMask;
            this.maxDelayedQueues = maxDelayedQueues;
        }

        /**
         * 假设线程B和线程C同时回收线程A的对象时，有可能会同时newQueue，就可能同时setHead，所以这里需要加锁
         * 以head==null的时候为例，
         * 加锁：
         * 线程B先执行，则head = 线程B的queue；之后线程C执行，此时将当前的head也就是线程B的queue作为线程C的queue的next，组成链表，之后设置head为线程C的queue
         * 不加锁：
         * 线程B先执行queue.setNext(head);此时线程B的queue.next=null->线程C执行queue.setNext(head);线程C的queue.next=null
         * -> 线程B执行head = queue;设置head为线程B的queue -> 线程C执行head = queue;设置head为线程C的queue
         *
         * 注意：此时线程B和线程C的queue没有连起来，则之后的poll()就不会从B进行查询。（B就是资源泄露）
         */
        //同步方法
        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            queue.setNext(head);
            head = queue;
        }

        int increaseCapacity(int expectedCapacity) {
            // 获取旧数组长度
            int newCapacity = elements.length;
            // 获取最大长度
            int maxCapacity = this.maxCapacity;
            // 不断扩容（每次扩容2倍），直到达到expectedCapacity或者新容量已经大于等于maxCapacity
            do {
                // 扩容2倍
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            // 上述的扩容有可能使新容量newCapacity>maxCapacity，这里取最小值
            newCapacity = min(newCapacity, maxCapacity);
            // 如果新旧容量不相等，进行实际扩容
            if (newCapacity != elements.length) {
                // 创建新数组，复制旧数组元素到新数组，并将新数组赋值给Stack.elements
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        /*
        当Stack中的DefaultHandle[]的size为0时，需要从其他线程的WeakOrderQueue中转移数据到Stack中的DefaultHandle[]，
        即scavenge()方法。当Stack中的DefaultHandle[]中最终有了数据时，
        直接获取最后一个元素，并进行一些防护性检查和元素的设置等。
         */
        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            /*
            如果该stack的DefaultHandle数组中还有对象可用，那么从该DefaultHandle数组中
            取出1个可用对象返回，如果该DefaultHandle数组没有可用的对象了，那么执行scavenge()方法，
            将head WeakOrderQueue中的head Link
            中的DefaultHandle数组转移到stack的DefaultHandle数组
             */
            if (size == 0) {
                if (!scavenge()) {
                    return null;
                }
                /**
                 * 由于在transfer(Stack<?> dst)的过程中，可能会将其他线程的WeakOrderQueue中
                 * 的DefaultHandle对象传递到当前的Stack,
                 * 所以size发生了变化，需要重新赋值
                 */
                size = this.size;
            }
            /**
             * 注意：因为一个Recycler<T>只能回收一种类型T的对象，
             * 所以element可以直接使用操作size来作为下标来进行获取
             */
            size --;
            //获取最后一个元素
            DefaultHandle ret = elements[size];
            // 将取过的元素置空
            elements[size] = null;
            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            // 置位,清空 该对象的被回收的标记
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            this.size = size;
            return ret;
        }

        //将WeakOrderQueue的头节点中的Link链表中的第一个link中的DefaultHandle数组转移到stack的DefaultHandle数组
        boolean scavenge() {
            // continue an existing scavenge, if any
            if (scavengeSome()) {
                return true;
            }

            //如果转移不了的话，重置
            // reset our scavenge cursor
            prev = null;
            cursor = head;
            return false;
        }

        //转移WeakOrderQueue中部分DefaultHandle到stack
        boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {
                //如果cursor还没有赋值，表示还没有转移过，将cursor设置为 第一个WorkOrderQueue节点
                prev = null;
                cursor = head;
                // 如果head==null，表示当前的Stack对象没有WeakOrderQueue，直接返回
                if (cursor == null) {
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                /**
                 * 将当前WeakOrderQueue的head Link的DefaultHandle数组转移到stack的DefaultHandle数组中
                 */
                if (cursor.transfer(this)) {
                    //如果转移成功，直接返回
                    success = true;
                    break;
                }
                WeakOrderQueue next = cursor.next;
                if (cursor.owner.get() == null) {
                    // 如果被转移的WorkOrderQueue对应的线程对象已经被gc，说明该WorkOrderQueue对应的线程已经被终止，则将该WorkOrderQueue对应的元素全部取出到stack中
                    //并且移动上一个 WorkOrderQueue对应的prev指针
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    /**
                     * 如果被转移的WeakOrderQueue的线程已经不可达了，则
                     * 1、如果该WeakOrderQueue中有数据，则将其中的数据 全部 转移到当前Stack中
                     * 2、将当前的WeakOrderQueue的前一个节点prev指向当前的WeakOrderQueue的下一个节点，即将当前的WeakOrderQueue从Queue链表中移除。方便后续GC
                     */
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    if (prev != null) {
                        prev.setNext(next);
                    }
                } else {
                    prev = cursor;
                }

                cursor = next;

            } while (cursor != null && !success);

            //设置指针
            this.prev = prev;
            this.cursor = cursor;
            return success;
        }

        //把该对象push到stack中
        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) {
                /**
                 * 如果该stack就是本线程的stack，那么直接把DefaultHandle放到该stack的数组里
                 */
                //同线程回收
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                pushNow(item);
            } else {
                /**
                 * 如果该stack不是本线程的stack，那么把该DefaultHandle放到该stack的WeakOrderQueue中
                 */
                //异线程回收
                //将对象放到WeakOrderQueue中
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                pushLater(item, currentThread);
            }
        }

        /**
         * 直接把被回收的DefaultHandle放到stack的数组里，如果数组满了那么扩展该数组为当前2倍大小
         * @param item
         */
        private void pushNow(DefaultHandle<?> item) {
            //如果元素被回收过，抛出异常
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            int size = this.size;
            //如果 stack大小已经大于最大容量
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            // stack中的elements扩容两倍，复制元素，将新数组赋值给stack.elements
            if (size == elements.length) {
                if (size == elements.length) {
                    elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
                }

                // 放置元素
                elements[size] = item;
                this.size = size + 1;
            }
        }

        /**
         * 先将item元素加入WeakOrderQueue，后续再从WeakOrderQueue中将元素压入Stack中
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            /**
             * Recycler有1个stack->WeakOrderQueue映射，每个stack会映射到1个WeakOrderQueue，这个WeakOrderQueue
             * 是该stack关联的其它线程WeakOrderQueue链表的head WeakOrderQueue。
             * 当其它线程回收对象到该stack时会创建1个WeakOrderQueue中并加到stack的WeakOrderQueue链表中。
             */
            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //根据本stack 获取对应的 WeakOrderQueue的head
            WeakOrderQueue queue = delayedRecycled.get(this);
            if (queue == null) {
                /**
                 *  // 如果DELAYED_RECYCLED中的key-value对已经达到了maxDelayedQueues
                 * 如果delayedRecycled满了那么将1个伪造的WeakOrderQueue（DUMMY）放到delayedRecycled中，
                 * 并丢弃该对象（DefaultHandle）
                 */
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                /**
                 * 创建stack对应的WeakOrderQueue，在WeakOrderQueue被gc时执行stack的 head对象
                 */
                if ((queue = WeakOrderQueue.allocate(this, thread)) == null) {
                    // drop object
                    return;
                }
                //将 WeakOrderQueue放入map中
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                // drop object
                return;
            }
            /**
             * 将对象放入到该stack对应的WeakOrderQueue中
             */
            queue.add(item);
        }

        /**
         * 两个drop的时机
         * 1、pushNow：当前线程将数据push到Stack中
         * 2、transfer：将其他线程的WeakOrderQueue中的数据转移到当前的Stack中
         */
        boolean dropHandle(DefaultHandle<?> handle) {
            if (!handle.hasBeenRecycled) {
                // 每8个对象：扔掉7个，回收一个
                // 回收的索引：handleRecycleCount - 0/8/16/24/32/...
                if ((++handleRecycleCount & ratioMask) != 0) {
                    // Drop the object.
                    return true;
                }
                // 设置已经被回收了的标志，实际上此处还没有被回收，在pushNow(DefaultHandle<T> item)接下来的逻辑就会进行回收
                // 对于pushNow(DefaultHandle<T> item)：该值仅仅用于控制是否执行
                // (++handleRecycleCount & ratioMask) != 0 这段逻辑，而不会用于阻止重复回收的操作，重复回收的操作由item.recycleId | item.lastRecycledId来阻止
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            //this 指的是stack
            return new DefaultHandle<T>(this);
        }
    }
}
