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

import io.netty.util.internal.EmptyArrays;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.StringUtil.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.NEWLINE;
import static io.netty.util.internal.StringUtil.simpleClassName;

public class ResourceLeakDetector<T> {

    private static final String PROP_LEVEL_OLD = "io.netty.leakDetectionLevel";
    private static final String PROP_LEVEL = "io.netty.leakDetection.level";
    private static final Level DEFAULT_LEVEL = Level.SIMPLE;

    private static final String PROP_TARGET_RECORDS = "io.netty.leakDetection.targetRecords";
    private static final int DEFAULT_TARGET_RECORDS = 4;

    /**
     * 只有>0时才记录record。如果当前链表长度已经达到了TARGET_RECORDS,
     * 则有(1/(2的(Math.min(numElements - TARGET_RECORDS, 30))次幂))的概率
     * 添加这个record到链表中；否则将本record替换原有的链表头。
     * 因此TARGET_RECORDS越大，链表越长，使用本record替换原有链表头的概率就越大
     */
    private static final int TARGET_RECORDS;

    /**
     * Represents the level of resource leak detection.
     */
    public enum Level {
        /**
         * Disables resource leak detection.
         */
        DISABLED,
        /**
         * Enables simplistic sampling resource leak detection which reports there is a leak or not,
         * at the cost of small overhead (default).
         */
        SIMPLE,
        /**
         * Enables advanced sampling resource leak detection which reports where the leaked object was accessed
         * recently at the cost of high overhead.
         */
        ADVANCED,
        /**
         * Enables paranoid resource leak detection which reports where the leaked object was accessed recently,
         * at the cost of the highest possible overhead (for testing purposes only).
         */
        PARANOID;

        /**
         * Returns level based on string value. Accepts also string that represents ordinal number of enum.
         *
         * @param levelStr - level string : DISABLED, SIMPLE, ADVANCED, PARANOID. Ignores case.
         * @return corresponding level or SIMPLE level in case of no match.
         */
        static Level parseLevel(String levelStr) {
            String trimmedLevelStr = levelStr.trim();
            for (Level l : values()) {
                if (trimmedLevelStr.equalsIgnoreCase(l.name()) || trimmedLevelStr.equals(String.valueOf(l.ordinal()))) {
                    return l;
                }
            }
            return DEFAULT_LEVEL;
        }
    }

    //检测等级
    private static Level level;

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ResourceLeakDetector.class);

    static {
        final boolean disabled;
        if (SystemPropertyUtil.get("io.netty.noResourceLeakDetection") != null) {
            disabled = SystemPropertyUtil.getBoolean("io.netty.noResourceLeakDetection", false);
            logger.debug("-Dio.netty.noResourceLeakDetection: {}", disabled);
            logger.warn(
                    "-Dio.netty.noResourceLeakDetection is deprecated. Use '-D{}={}' instead.",
                    PROP_LEVEL, DEFAULT_LEVEL.name().toLowerCase());
        } else {
            disabled = false;
        }

        Level defaultLevel = disabled? Level.DISABLED : DEFAULT_LEVEL;

        // First read old property name
        String levelStr = SystemPropertyUtil.get(PROP_LEVEL_OLD, defaultLevel.name());

        // If new property name is present, use it
        levelStr = SystemPropertyUtil.get(PROP_LEVEL, levelStr);
        Level level = Level.parseLevel(levelStr);

        TARGET_RECORDS = SystemPropertyUtil.getInt(PROP_TARGET_RECORDS, DEFAULT_TARGET_RECORDS);

        ResourceLeakDetector.level = level;
        if (logger.isDebugEnabled()) {
            logger.debug("-D{}: {}", PROP_LEVEL, level.name().toLowerCase());
            logger.debug("-D{}: {}", PROP_TARGET_RECORDS, TARGET_RECORDS);
        }
    }

    //默认检测概率 1/128
    // There is a minor performance benefit in TLR if this is a power of 2.
    static final int DEFAULT_SAMPLING_INTERVAL = 128;

    /**
     * @deprecated Use {@link #setLevel(Level)} instead.
     */
    @Deprecated
    public static void setEnabled(boolean enabled) {
        setLevel(enabled? Level.SIMPLE : Level.DISABLED);
    }

    /**
     * Returns {@code true} if resource leak detection is enabled.
     */
    public static boolean isEnabled() {
        return getLevel().ordinal() > Level.DISABLED.ordinal();
    }

    /**
     * Sets the resource leak detection level.
     */
    public static void setLevel(Level level) {
        if (level == null) {
            throw new NullPointerException("level");
        }
        ResourceLeakDetector.level = level;
    }

    /**
     * Returns the current resource leak detection level.
     */
    public static Level getLevel() {
        return level;
    }

    //内存泄漏判断。
////在生成DefaultResourceLeak对象时 会将 该DefaultResourceLeak对象 放入allLeaks中;
    //当对象的 引用计数为 0 时，就从 allLeaks中将 对应的 DefaultResourceLeak对象删除
    // 因此，allLeaks中是否包含自己就作为是否正确release和GC的标准
    //如果 在指向的资源被gc之后，allLeaks中还包含自己，则表示内存泄漏了
    //在判断  gc队列中元素 是否被正确释放时，就是这么判断的
    /** the collection of active resources */
    private final ConcurrentMap<DefaultResourceLeak<?>, LeakEntry> allLeaks = PlatformDependent.newConcurrentHashMap();

    //gc回收队列
    private final ReferenceQueue<Object> refQueue = new ReferenceQueue<Object>();
    /**
     * 已经报告过的泄漏，Map<DefaultResourceLeak生成的report记录，true>
     */
    private final ConcurrentMap<String, Boolean> reportedLeaks = PlatformDependent.newConcurrentHashMap();

    //本ResourceLeakDetector对应的资源类型
    private final String resourceType;
    //检测概率，默认为 1/128
    private final int samplingInterval;

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType) {
        this(simpleClassName(resourceType));
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType) {
        this(resourceType, DEFAULT_SAMPLING_INTERVAL, Long.MAX_VALUE);
    }

    /**
     * @deprecated Use {@link ResourceLeakDetector#ResourceLeakDetector(Class, int)}.
     * <p>
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     *
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval, long maxActive) {
        this(resourceType, samplingInterval);
    }

    /**
     * This should not be used directly by users of {@link ResourceLeakDetector}.
     * Please use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class)}
     * or {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}
     */
    @SuppressWarnings("deprecation")
    public ResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        this(simpleClassName(resourceType), samplingInterval, Long.MAX_VALUE);
    }

    /**
     * @deprecated use {@link ResourceLeakDetectorFactory#newResourceLeakDetector(Class, int, long)}.
     * <p>
     * @param maxActive This is deprecated and will be ignored.
     */
    @Deprecated
    public ResourceLeakDetector(String resourceType, int samplingInterval, long maxActive) {
        if (resourceType == null) {
            throw new NullPointerException("resourceType");
        }

        this.resourceType = resourceType;
        this.samplingInterval = samplingInterval;
    }

    /**
     * Creates a new {@link ResourceLeak} which is expected to be closed via {@link ResourceLeak#close()} when the
     * related resource is deallocated.
     *
     * @return the {@link ResourceLeak} or {@code null}
     * @deprecated use {@link #track(Object)}
     */
    @Deprecated
    public final ResourceLeak open(T obj) {
        return track0(obj);
    }

    /**
     * Creates a new {@link ResourceLeakTracker} which is expected to be closed via
     * {@link ResourceLeakTracker#close(Object)} when the related resource is deallocated.
     *
     * @return the {@link ResourceLeakTracker} or {@code null}
     */
    /**
     * 创建一个ResourceLeakTracker实例来跟踪一个池化资源。
     * 如果触发了检测概率，则会创建ResourceLeakTracker实例并检测内存泄漏情况
     * (从gc回收队列中获取被gc的ResourceLeakTracker 并判断在allLeaks中是否存在
     *  1.如果存在，则表示内存泄漏，因为在 调用计数对象的release()方法时，如果计数为0,会调用ResourceLeakTracker对象的close方法，从allLeaks中删除)
     *  2.如果不存在，则表示没有内存泄漏
     * 它需要在这个资源被释放的时候调用close方法
     */
    @SuppressWarnings("unchecked")
    public final ResourceLeakTracker<T> track(T obj) {
        return track0(obj);
    }

    /**
     * 具体开创建ResourceLeakTracker逻辑
     */
    @SuppressWarnings("unchecked")
    private DefaultResourceLeak track0(T obj) {
        Level level = ResourceLeakDetector.level;
        // 如果跟踪登记是Disable，直接返回null
        if (level == Level.DISABLED) {
            return null;
        }

        // 如果等级小于Paranoid'
        if (level.ordinal() < Level.PARANOID.ordinal()) {
            // 如果这次随机触发了采样间隔
            // 就报告现有的泄漏
            // 并返回一个DefaultResourceLeak示例来跟踪当前资源
            // 注意为了性能，这里使用了ThreadLocalRandom
            if ((PlatformDependent.threadLocalRandom().nextInt(samplingInterval)) == 0) {
                reportLeak();
                return new DefaultResourceLeak(obj, refQueue, allLeaks);
            }
            // 否则如果没触发采样间隔
            // 则直接返回null 表示不用跟踪这次资源
            return null;
        }
        // 走到这里说明每次资源创建都需要跟踪
        reportLeak();
        return new DefaultResourceLeak(obj, refQueue, allLeaks);
    }

    /**
     * 无脑循环ReferenceQueue，清空之
     */
    private void clearRefQueue() {
        for (;;) {
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            if (ref == null) {
                break;
            }
            ref.dispose();
        }
    }

    /**
     * 这个方法用来判断ReferenceQueue中是否存在需要报告的泄漏
     */
    private void reportLeak() {
        // 如果没有启用error日志
        // 仅仅清空当前ReferenceQueue即可
        if (!logger.isErrorEnabled()) {
            clearRefQueue();
            return;
        }

        // 检查和报告之前所有的泄漏
        // Detect and report previous leaks.
        for (;;) {
            // 从ReferenceQueue中poll一个对象
            @SuppressWarnings("unchecked")
            DefaultResourceLeak ref = (DefaultResourceLeak) refQueue.poll();
            // 为空说明已经清空了
            if (ref == null) {
                break;
            }

            // 如果这个DefaultResourceLeak对象的dispose方法返回false
            // 说明它所跟踪监控的资源已经被正确释放，不存在泄露
            //返回false:说明已经从 allLeaks中删除
            //返回true:说明指定资源gc了还没有从allLeaks中删除，存在内存泄漏
            if (!ref.dispose()) {
                continue;
            }

            // 到这里说明已经产生泄露了
            // 获取这个泄露的相关记录的字符串
            String records = ref.toString();
            // 看看这个泄漏有没有出现过
            if (reportedLeaks.putIfAbsent(records, Boolean.TRUE) == null) {
                if (records.isEmpty()) {
                    // 如果字符串为空说明目前的检测级别小于等于SIMPLE,只报告泄漏的异常类型，不打印调用链
                    // 就需要报告为untracked的泄漏
                    // 这个方法就直接记录日志，没什么可看的
                    reportUntracedLeak(resourceType);
                } else {
                    // 否则就是报告为tracked的泄漏
                    // 这个方法就直接记录日志就好，没什么可看的
                    reportTracedLeak(resourceType, records);
                }
            }
        }
    }

    /**
     * This method is called when a traced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportTracedLeak(String resourceType, String records) {
        logger.error(
                "LEAK: {}.release() was not called before it's garbage-collected. " +
                "See http://netty.io/wiki/reference-counted-objects.html for more information.{}",
                resourceType, records);
    }

    /**
     * This method is called when an untraced leak is detected. It can be overridden for tracking how many times leaks
     * have been detected.
     */
    protected void reportUntracedLeak(String resourceType) {
        logger.error("LEAK: {}.release() was not called before it's garbage-collected. " +
                "Enable advanced leak reporting to find out where the leak occurred. " +
                "To enable advanced leak reporting, " +
                "specify the JVM option '-D{}={}' or call {}.setLevel() " +
                "See http://netty.io/wiki/reference-counted-objects.html for more information.",
                resourceType, PROP_LEVEL, Level.ADVANCED.name().toLowerCase(), simpleClassName(this));
    }

    /**
     * @deprecated This method will no longer be invoked by {@link ResourceLeakDetector}.
     */
    @Deprecated
    protected void reportInstancesLeak(String resourceType) {
    }

    @SuppressWarnings("deprecation")
    private static final class DefaultResourceLeak<T>
            extends WeakReference<Object> implements ResourceLeakTracker<T>, ResourceLeak {

        //cas替换 链表头节点
        //初始时:头节点为 一个固定的默认Record节点
        //指向的资源对象被关闭时: 设置为null
        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicReferenceFieldUpdater<DefaultResourceLeak<?>, Record> headUpdater =
                (AtomicReferenceFieldUpdater)
                        AtomicReferenceFieldUpdater.newUpdater(DefaultResourceLeak.class, Record.class, "head");

        //由于TRARGET_SOURCE限制 随机到概率主动丢弃的 记录数量
        @SuppressWarnings("unchecked") // generics and updaters do not mix.
        private static final AtomicIntegerFieldUpdater<DefaultResourceLeak<?>> droppedRecordsUpdater =
                (AtomicIntegerFieldUpdater)
                        AtomicIntegerFieldUpdater.newUpdater(DefaultResourceLeak.class, "droppedRecords");

        /**
         * Record链表的头节点
         */
        @SuppressWarnings("unused")
        private volatile Record head;
        /**
         * 由于TARGET_RECORDS限制主动丢弃的record数量
         */
        @SuppressWarnings("unused")
        private volatile int droppedRecords;

        //在生成DefaultResourceLeak对象时 会将 该DefaultResourceLeak对象 放入allLeaks中;
        //当对象的 引用计数为 0 时，就从 allLeaks中将 对应的 DefaultResourceLeak对象删除
        // 因此，allLeaks中是否包含自己就作为是否正确release和GC的标准
        //如果 在指向的资源被gc之后，allLeaks中还包含自己，则表示内存泄漏了
        //在判断  gc队列中元素 是否被正确释放时，就是这么判断的
        private final ConcurrentMap<DefaultResourceLeak<?>, LeakEntry> allLeaks;
        //指向的资源的hashCode，只是用来判断了下 调用close方法(在引用计数为0时调用) 和初始化创建的时候指定的资源是否是同一个
        private final int trackedHash;

        /**
         * DefaultResourceLeak构造方法
         * @param referent
         * @param refQueue
         * @param allLeaks
         */
        DefaultResourceLeak(
                Object referent,
                ReferenceQueue<Object> refQueue,
                ConcurrentMap<DefaultResourceLeak<?>, LeakEntry> allLeaks) {
            // 调用WeakReference的调用方法
            // 注意传入了ReferenceQueue， 完成GC的通知
            super(referent, refQueue);

            assert referent != null;

            // 这里生成了我们引用指向的资源的hashCode
            // 注意这里我们存储了hashCode而非资源对象本身
            // 因为如果存储资源对象本身的话我们就形成了强引用，导致资源不可能被GC
            // Store the hash of the tracked object to later assert it in the close(...) method.
            // It's important that we not store a reference to the referent as this would disallow it from
            // be collected via the WeakReference.
            trackedHash = System.identityHashCode(referent);
            // 将当前的DefaultResourceLeak示例加入到allLeaks集合里面
            // 这个集合是由它跟踪的资源所属的ResourceLeakDetector管理
            // 这个集合在后面判断资源是否正确释放扮演重要角色
            allLeaks.put(this, LeakEntry.INSTANCE);
            // Create a new Record so we always have the creation stacktrace included.
            //设置头节点
            headUpdater.set(this, new Record(Record.BOTTOM));
            this.allLeaks = allLeaks;
        }

        /**
         * 单纯记录一个调用点，没有任何额外提示信息
         */
        @Override
        public void record() {
            record0(null);
        }

        /**
         * 记录一个调用点，并附上额外信息
         */
        @Override
        public void record(Object hint) {
            record0(hint);
        }

        /**
         * This method works by exponentially backing off as more records are present in the stack. Each record has a
         * 1 / 2^n chance of dropping the top most record and replacing it with itself. This has a number of convenient
         * properties:
         *
         * <ol>
         * <li>  The current record is always recorded. This is due to the compare and swap dropping the top most
         *       record, rather than the to-be-pushed record.
         * <li>  The very last access will always be recorded. This comes as a property of 1.
         * <li>  It is possible to retain more records than the target, based upon the probability distribution.
         * <li>  It is easy to keep a precise record of the number of elements in the stack, since each element has to
         *     know how tall the stack is.
         * </ol>
         *
         * In this particular implementation, there are also some advantages. A thread local random is used to decide
         * if something should be recorded. This means that if there is a deterministic access pattern, it is now
         * possible to see what other accesses occur, rather than always dropping them. Second, after
         * {@link #TARGET_RECORDS} accesses, backoff occurs. This matches typical access patterns,
         * where there are either a high number of accesses (i.e. a cached buffer), or low (an ephemeral buffer), but
         * not many in between.
         *
         * The use of atomics avoids serializing a high number of accesses, when most of the records will be thrown
         * away. High contention only happens when there are very few existing records, which is only likely when the
         * object isn't shared! If this is a problem, the loop can be aborted and the record dropped, because another
         * thread won the race.
         */
        /**
         * 这个函数非常有意思
         * 有一个预设的TARGET_RECORDS字段
         * 这里有个问题，如果这个资源会在很多地方被记录，
         * 那么这个跟踪这个资源的DefaultResourceLeak的Record就会有很多
         * 但并不是每个记录都需要被记录，否则就会对内存和运行都会造成压力
         * 因为每个Record都会记录整个调用栈
         * 因此需要对记录做取舍
         * 这里有几个原则
         * 1. 所有record都会用一根单向链表来保存
         * 2. 最新的record永远都会被记录
         * 3. 小于TARGET_RECORDS数目的record也会被记录
         * 4. 当数目大于等于TARGET_RECORDS的时候，就会根据概率选择是用最新的record替换掉
         *    当前链表中头上的record(保证链表长度不会增加)，还是仅仅添加到头上的record之前
         *    (也就是增加链表长度)，当链表长度越大时，替换的概率也越大
         * @param hint
         */
        private void record0(Object hint) {
            // 如果TARGET_RECORDS小于等于0 表示不记录
            // Check TARGET_RECORDS > 0 here to avoid similar check before remove from and add to lastRecords
            if (TARGET_RECORDS > 0) {
                Record oldHead;
                Record prevHead;
                Record newHead;
                boolean dropped;
                do {
                    // 如果链表头为null，说明 指向的资源已经 已经close了
                    if ((prevHead = oldHead = headUpdater.get(this)) == null) {
                        // already closed.
                        return;
                    }
                    // 获取当前链表长度
                    final int numElements = oldHead.pos + 1;
                    // 如果当前链表长度大于等于TARGET_RECORDS
                    if (numElements >= TARGET_RECORDS) {
                        // 获取是否替换的概率，先获取一个因子n
                        // 这个n最多为30，最小为链表长度 - TARGET_RECORDS
                        final int backOffFactor = Math.min(numElements - TARGET_RECORDS, 30);
                        // 这里有 1 / 2^n的概率来添加这个record而不丢弃原有的链表头record
                        // (2^n-1) / 2^n 的概率丢弃 原有的链表头，将这个record作为新的链表头
                        if (dropped = PlatformDependent.threadLocalRandom().nextInt(1 << backOffFactor) != 0) {
                            prevHead = oldHead.next;
                        }
                    } else {
                        dropped = false;
                    }
                    // cas 更新record链表
                    newHead = hint != null ? new Record(prevHead, hint) : new Record(prevHead);
                } while (!headUpdater.compareAndSet(this, oldHead, newHead));
                // 增加丢弃的record数量
                if (dropped) {
                    droppedRecordsUpdater.incrementAndGet(this);
                }
            }
        }

        //// 如果这个DefaultResourceLeak对象的dispose方法返回false
        // 说明它所跟踪监控的资源已经被正确释放，不存在泄露
        //其实就是判断allLeaks中还有没有 对应的对象
        boolean dispose() {
            // 清理对资源对象的引用
            clear();
            // 直接使用allLeaks.remove(this) 的结果来
            // 如果remove成功就说明之前close没有调用成功
            // 也就说明了这个监控对象并没有调用足够的release来完成资源释放
            // 如果remove失败说明之前已经完成了close的调用，一切正常
            return allLeaks.remove(this, LeakEntry.INSTANCE);
        }

        //todo 高亮:在ByteBuf.release()将引用计数减为0的时候被调用，因此所有在gc前正确释放的DefaultResourceLeak监控对象 都会在 allLeaks中删除
        @Override
        public boolean close() {
            // 从allLeaks 集合中去除自己
            // allLeaks中是否包含自己就作为是否正确release和GC的标准
            //1.如果 在指向的资源被gc之后，allLeaks中还包含自己，则表示内存泄漏了
            // Use the ConcurrentMap remove method, which avoids allocating an iterator.
            if (allLeaks.remove(this, LeakEntry.INSTANCE)) {
                // Call clear so the reference is not even enqueued.
                // 如果成功去除自己，说明是正常流程
                // 清除掉对资源对象的引用
                clear();
                // 设置链表头record到null
                headUpdater.set(this, null);
                // 返回关闭成功
                return true;
            }
            // 说明自己已经被去除了，可能是重复close，或者是存在泄露，返回关闭失败
            return false;
        }

        // 外部调用的都是带参数的close方法
        @Override
        public boolean close(T trackedObject) {
            // 保证释放和跟踪的是同一个对象
            // Ensure that the object that was tracked is the same as the one that was passed to close(...).
            assert trackedHash == System.identityHashCode(trackedObject);

            // We need to actually do the null check of the trackedObject after we close the leak because otherwise
            // we may get false-positives reported by the ResourceLeakDetector. This can happen as the JIT / GC may
            // be able to figure out that we do not need the trackedObject anymore and so already enqueue it for
            // collection before we actually get a chance to close the enclosing ResourceLeak.
            // 调用真正close的逻辑
            return close() && trackedObject != null;
        }

        //打印record链表
        @Override
        public String toString() {
            Record oldHead = headUpdater.getAndSet(this, null);
            if (oldHead == null) {
                // Already closed
                return EMPTY_STRING;
            }

            //由于 TARGET_RECORDS 限制 丢弃了多少个Record
            final int dropped = droppedRecordsUpdater.get(this);
            int duped = 0;

            int present = oldHead.pos + 1;
            // Guess about 2 kilobytes per stack trace
            StringBuilder buf = new StringBuilder(present * 2048).append(NEWLINE);
            buf.append("Recent access records: ").append(NEWLINE);

            int i = 1;
            Set<String> seen = new HashSet<String>(present);
            for (; oldHead != Record.BOTTOM; oldHead = oldHead.next) {
                String s = oldHead.toString();
                if (seen.add(s)) {
                    if (oldHead.next == Record.BOTTOM) {
                        buf.append("Created at:").append(NEWLINE).append(s);
                    } else {
                        buf.append('#').append(i++).append(':').append(NEWLINE).append(s);
                    }
                } else {
                    duped++;
                }
            }

            //如果有重复的record记录，表示有 资源对象被复制
            if (duped > 0) {
                buf.append(": ")
                        .append(dropped)
                        .append(" leak records were discarded because they were duplicates")
                        .append(NEWLINE);
            }

            if (dropped > 0) {
                buf.append(": ")
                   .append(dropped)
                   .append(" leak records were discarded because the leak record count is targeted to ")
                   .append(TARGET_RECORDS)
                   .append(". Use system property ")
                   .append(PROP_TARGET_RECORDS)
                   .append(" to increase the limit.")
                   .append(NEWLINE);
            }

            buf.setLength(buf.length() - NEWLINE.length());
            return buf.toString();
        }
    }

    //打印Record调用栈时会排除一些不需要打印的调用栈
    //每2个元素，第一个元素为类名，第二个元素为方法名
    private static final AtomicReference<String[]> excludedMethods =
            new AtomicReference<String[]>(EmptyArrays.EMPTY_STRINGS);

    public static void addExclusions(Class clz, String ... methodNames) {
        Set<String> nameSet = new HashSet<String>(Arrays.asList(methodNames));
        // Use loop rather than lookup. This avoids knowing the parameters, and doesn't have to handle
        // NoSuchMethodException.
        for (Method method : clz.getDeclaredMethods()) {
            if (nameSet.remove(method.getName()) && nameSet.isEmpty()) {
                break;
            }
        }
        if (!nameSet.isEmpty()) {
            throw new IllegalArgumentException("Can't find '" + nameSet + "' in " + clz.getName());
        }
        String[] oldMethods;
        String[] newMethods;
        do {
            oldMethods = excludedMethods.get();
            newMethods = Arrays.copyOf(oldMethods, oldMethods.length + 2 * methodNames.length);
            for (int i = 0; i < methodNames.length; i++) {
                newMethods[oldMethods.length + i * 2] = clz.getName();
                newMethods[oldMethods.length + i * 2 + 1] = methodNames[i];
            }
        } while (!excludedMethods.compareAndSet(oldMethods, newMethods));
    }

    //继承了 Throwable(使用Throwable.getStackTrace()方法
    private static final class Record extends Throwable {
        private static final long serialVersionUID = 6065153674892850720L;

        //第一条Record
        private static final Record BOTTOM = new Record();

        //注释
        private final String hintString;
        //下一个Record
        private final Record next;
        //Record链表中的第几个 Record，从0开始
        private final int pos;

        Record(Record next, Object hint) {
            // This needs to be generated even if toString() is never called as it may change later on.
            hintString = hint instanceof ResourceLeakHint ? ((ResourceLeakHint) hint).toHintString() : hint.toString();
            this.next = next;
            this.pos = next.pos + 1;
        }

        Record(Record next) {
           hintString = null;
           this.next = next;
           this.pos = next.pos + 1;
        }

        // Used to terminate the stack
        private Record() {
            hintString = null;
            next = null;
            pos = -1;
        }

        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(2048);
            if (hintString != null) {
                buf.append("\tHint: ").append(hintString).append(NEWLINE);
            }

            // 依托于Throwable的getStackTrace方法，获取创建时候的调用栈
            // Append the stack trace.
            StackTraceElement[] array = getStackTrace();
            // 跳过最开始的三个栈元素，因为它们就是record方法的那些栈信息，没必要显示了
            // Skip the first three elements.
            out: for (int i = 3; i < array.length; i++) {
                StackTraceElement element = array[i];
                // 去除一些不必要的方法信息
                // Strip the noisy stack trace elements.
                String[] exclusions = excludedMethods.get();
                for (int k = 0; k < exclusions.length; k += 2) {
                    if (exclusions[k].equals(element.getClassName())
                            && exclusions[k + 1].equals(element.getMethodName())) {
                        continue out;
                    }
                }

                // 格式化
                buf.append('\t');
                buf.append(element.toString());
                buf.append(NEWLINE);
            }
            return buf.toString();
        }
    }

    private static final class LeakEntry {
        static final LeakEntry INSTANCE = new LeakEntry();
        private static final int HASH = System.identityHashCode(INSTANCE);

        private LeakEntry() {
        }

        @Override
        public int hashCode() {
            return HASH;
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this;
        }
    }
}
