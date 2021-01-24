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
package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

import static io.netty.util.internal.StringUtil.simpleClassName;

/**
 * A {@link Timer} optimized for approximated I/O timeout scheduling.
 * <p>
 * <h3>Tick Duration</h3>
 * <p>
 * As described with 'approximated', this timer does not execute the scheduled
 * {@link TimerTask} on time.  {@link HashedWheelTimer}, on every tick, will
 * check if there are any {@link TimerTask}s behind the schedule and execute
 * them.
 * <p>
 * You can increase or decrease the accuracy of the execution timing by
 * specifying smaller or larger tick duration in the constructor.  In most
 * network applications, I/O timeout does not need to be accurate.  Therefore,
 * the default tick duration is 100 milliseconds and you will not need to try
 * different configurations in most cases.
 * <p>
 * <h3>Ticks per Wheel (Wheel Size)</h3>
 * <p>
 * {@link HashedWheelTimer} maintains a data structure called 'wheel'.
 * To put simply, a wheel is a hash table of {@link TimerTask}s whose hash
 * function is 'dead line of the task'.  The default number of ticks per wheel
 * (i.e. the size of the wheel) is 512.  You could specify a larger value
 * if you are going to schedule a lot of timeouts.
 * <p>
 * <h3>Do not create many instances.</h3>
 * <p>
 * {@link HashedWheelTimer} creates a new thread whenever it is instantiated and
 * started.  Therefore, you should make sure to create only one instance and
 * share it across your application.  One of the common mistakes, that makes
 * your application unresponsive, is to create a new instance for every connection.
 * <p>
 * <h3>Implementation Details</h3>
 * <p>
 * {@link HashedWheelTimer} is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 */
public class HashedWheelTimer implements Timer {

    static final InternalLogger logger =
            InternalLoggerFactory.getInstance(HashedWheelTimer.class);

    //已经创建并且没有被gc的 时间轮的 数量，如果超过一定数量，则打印错误日志
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();
    // 控制打印 时间轮创建数量过多的日志，只打印一次
    private static final AtomicBoolean WARNED_TOO_MANY_INSTANCES = new AtomicBoolean();
    // 已经创建的 时间轮的 数量，如果已经创建并且没有被gc的时间轮的数量超过64，则打印错误日志
    private static final int INSTANCE_COUNT_LIMIT = 64;
    //内存泄漏检测
    private static final ResourceLeakDetector<HashedWheelTimer> leakDetector = ResourceLeakDetectorFactory.instance()
            .newResourceLeakDetector(HashedWheelTimer.class, 1);
    //worker线程状态更新
    private static final AtomicIntegerFieldUpdater<HashedWheelTimer> WORKER_STATE_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimer.class, "workerState");

    //内存泄漏检测(默认检测，如果HashedWheelTimer不是 守护线程也一定检测)
    private final ResourceLeakTracker<HashedWheelTimer> leak;
    // worker线程 runnable
    private final Worker worker = new Worker();
    // worker线程
    private final Thread workerThread;

    public static final int WORKER_STATE_INIT = 0;
    public static final int WORKER_STATE_STARTED = 1;
    public static final int WORKER_STATE_SHUTDOWN = 2;
    //worker线程的状态，WORKER_STATE_INIT、WORKER_STATE_STARTED、WORKER_STATE_SHUTDOWN
    @SuppressWarnings({"unused", "FieldMayBeFinal"})
    private volatile int workerState; // 0 - init, 1 - started, 2 - shut down

    // 每个tick的时间间隔(纳秒)
    private final long tickDuration;
    // 时间轮，其中包含 bucket数组，数组元素数量一定是2的幂次方(就算不是也不被规范化到是2的幂次方)
    private final HashedWheelBucket[] wheel;
    // 掩码，wheel.length-1,用于计算 定时任务 最后存放在时间轮的哪个位置
    private final int mask;
    //用户等待 worker线程初始化完毕
    private final CountDownLatch startTimeInitialized = new CountDownLatch(1);
    //新添加还没有进入时间轮的 定时任务，MPSC
    private final Queue<HashedWheelTimeout> timeouts = PlatformDependent.newMpscQueue();
    //记录要取消的定时任务，MPSC
    private final Queue<HashedWheelTimeout> cancelledTimeouts = PlatformDependent.newMpscQueue();
    //记录已经添加 还没有执行(未取消) 的定时任务数(包括还没有进入时间轮和已经进入时间轮的任务)
    private final AtomicLong pendingTimeouts = new AtomicLong(0);
    // pendingTimeouts队列的最大长度，小于0等于0则不限制
    private final long maxPendingTimeouts;
    //Woker执行的开始时间，starttime为0表示还没有初始化
    private volatile long startTime;

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}), default tick duration, and
     * default number of ticks per wheel.
     */
    public HashedWheelTimer() {
        this(Executors.defaultThreadFactory());
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}) and default number of ticks
     * per wheel.
     *
     * @param tickDuration the duration between tick
     * @param unit         the time unit of the {@code tickDuration}
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit) {
        this(Executors.defaultThreadFactory(), tickDuration, unit);
    }

    /**
     * Creates a new timer with the default thread factory
     * ({@link Executors#defaultThreadFactory()}).
     *
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(Executors.defaultThreadFactory(), tickDuration, unit, ticksPerWheel);
    }

    /**
     * Creates a new timer with the default tick duration and default number of
     * ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @throws NullPointerException if {@code threadFactory} is {@code null}
     */
    public HashedWheelTimer(ThreadFactory threadFactory) {
        this(threadFactory, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new timer with the default number of ticks per wheel.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if {@code tickDuration} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory, long tickDuration, TimeUnit unit) {
        this(threadFactory, tickDuration, unit, 512);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, true);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory a {@link ThreadFactory} that creates a
     *                      background {@link Thread} which is dedicated to
     *                      {@link TimerTask} execution.
     * @param tickDuration  the duration between tick
     * @param unit          the time unit of the {@code tickDuration}
     * @param ticksPerWheel the size of the wheel
     * @param leakDetection {@code true} if leak detection should be enabled always,
     *                      if false it will only be enabled if the worker thread is not
     *                      a daemon thread.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection) {
        this(threadFactory, tickDuration, unit, ticksPerWheel, leakDetection, -1);
    }

    /**
     * Creates a new timer.
     *
     * @param threadFactory      a {@link ThreadFactory} that creates a
     *                           background {@link Thread} which is dedicated to
     *                           {@link TimerTask} execution.
     * @param tickDuration       the duration between tick
     * @param unit               the time unit of the {@code tickDuration}
     * @param ticksPerWheel      the size of the wheel
     * @param leakDetection      {@code true} if leak detection should be enabled always,
     *                           if false it will only be enabled if the worker thread is not
     *                           a daemon thread.
     * @param maxPendingTimeouts The maximum number of pending timeouts after which call to
     *                           {@code newTimeout} will result in
     *                           {@link java.util.concurrent.RejectedExecutionException}
     *                           being thrown. No maximum pending timeouts limit is assumed if
     *                           this value is 0 or negative.
     * @throws NullPointerException     if either of {@code threadFactory} and {@code unit} is {@code null}
     * @throws IllegalArgumentException if either of {@code tickDuration} and {@code ticksPerWheel} is &lt;= 0
     */
    public HashedWheelTimer(
            ThreadFactory threadFactory,
            long tickDuration, TimeUnit unit, int ticksPerWheel, boolean leakDetection,
            long maxPendingTimeouts) {
        // 线程工厂，用于创建我们的worker线程
        if (threadFactory == null) {
            throw new NullPointerException("threadFactory");
        }
        // 一个tick的时间单位
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        // 一个tick的时间间隔
        if (tickDuration <= 0) {
            throw new IllegalArgumentException("tickDuration must be greater than 0: " + tickDuration);
        }
        // 时间轮上一共 有多少个tick/bucket
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException("ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }

        //创建时间轮
        // Normalize ticksPerWheel to power of two and initialize the wheel.
        wheel = createWheel(ticksPerWheel);
        // 计算位运算需要的掩码
        mask = wheel.length - 1;

        // 转换时间间隔到纳秒
        // Convert tickDuration to nanos.
        this.tickDuration = unit.toNanos(tickDuration);

        // 防止溢出
        // Prevent overflow.
        if (this.tickDuration >= Long.MAX_VALUE / wheel.length) {
            throw new IllegalArgumentException(String.format(
                    "tickDuration: %d (expected: 0 < tickDuration in nanos < %d",
                    tickDuration, Long.MAX_VALUE / wheel.length));
        }
        // 创建worker线程
        workerThread = threadFactory.newThread(worker);
        //// 处理内存泄漏检测
        leak = leakDetection || !workerThread.isDaemon() ? leakDetector.track(this) : null;
        // 设置最大等待被添加到 时间轮中的定时任务数
        this.maxPendingTimeouts = maxPendingTimeouts;
        //已经创建的 时间轮的 数量，如果超过一定数量，则打印错误日志
        if (INSTANCE_COUNTER.incrementAndGet() > INSTANCE_COUNT_LIMIT &&
                WARNED_TOO_MANY_INSTANCES.compareAndSet(false, true)) {
            reportTooManyInstances();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            //对于在gc的时候也没有将timer shudown的情况，将状态设置为 shutdown并释放 占据的名额
            // This object is going to be GCed and it is assumed the ship has sailed to do a proper shutdown. If
            // we have not yet shutdown then we want to make sure we decrement the active instance count.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                INSTANCE_COUNTER.decrementAndGet();
            }
        }
    }

    //创建时间轮
    private static HashedWheelBucket[] createWheel(int ticksPerWheel) {
        // 处理时间轮太小或者太大造成的异常
        if (ticksPerWheel <= 0) {
            throw new IllegalArgumentException(
                    "ticksPerWheel must be greater than 0: " + ticksPerWheel);
        }
        if (ticksPerWheel > 1073741824) {
            throw new IllegalArgumentException(
                    "ticksPerWheel may not be greater than 2^30: " + ticksPerWheel);
        }

        // 将时间轮的大小规范化到2的n次方，这样可以用位运算来处理mod操作，提高效率
        ticksPerWheel = normalizeTicksPerWheel(ticksPerWheel);
        // 创建每个bucket
        HashedWheelBucket[] wheel = new HashedWheelBucket[ticksPerWheel];
        for (int i = 0; i < wheel.length; i++) {
            wheel[i] = new HashedWheelBucket();
        }
        return wheel;
    }

    //规范化到 2的幂次方
    private static int normalizeTicksPerWheel(int ticksPerWheel) {
        int normalizedTicksPerWheel = 1;
        // 不断地左移位直到找到大于等于时间轮大小的2的n次方出现
        while (normalizedTicksPerWheel < ticksPerWheel) {
            normalizedTicksPerWheel <<= 1;
        }
        return normalizedTicksPerWheel;
    }

    /**
     * Starts the background thread explicitly.  The background thread will
     * start automatically on demand even if you did not call this method.
     *
     * @throws IllegalStateException if this timer has been
     *                               {@linkplain #stop() stopped} already
     */
    public void start() {
        // 针对worker的状态进行switch
        switch (WORKER_STATE_UPDATER.get(this)) {
            // 如果是初始化
            case WORKER_STATE_INIT:
                // 如果能cas更新到开始状态
                if (WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_INIT, WORKER_STATE_STARTED)) {
                    // 那就启动worker线程
                    workerThread.start();
                }
                break;
            // 如果已经处于启动，自然什么都不用做
            case WORKER_STATE_STARTED:
                break;
            // 如果已经shutdown， 那也就进入了非法状态
            case WORKER_STATE_SHUTDOWN:
                throw new IllegalStateException("cannot be started once stopped");
            default:
                throw new Error("Invalid WorkerState");
        }

        // 这里需要同步等待worker线程启动并完成startTime初始化的工作
        // Wait until the startTime is initialized by the worker.
        while (startTime == 0) {
            try {
                startTimeInitialized.await();
            } catch (InterruptedException ignore) {
                // Ignore - it will be ready very soon.
            }
        }
    }

    //关闭 work线程，// 返回还没执行的定时任务
    @Override
    public Set<Timeout> stop() {
        // 判断当前线程是否是worker线程，如果 是，就表示 肯定是 定时任务中 关闭了，直接报错
        if (Thread.currentThread() == workerThread) {
            throw new IllegalStateException(
                    HashedWheelTimer.class.getSimpleName() +
                            ".stop() cannot be called from " +
                            TimerTask.class.getSimpleName());
        }
        // 状态流转只能如下:
        // init-> shutdown
        // init -> started
        //started-> shutdown
        // 将 worker状态 有 started改为 shutdown
        if (!WORKER_STATE_UPDATER.compareAndSet(this, WORKER_STATE_STARTED, WORKER_STATE_SHUTDOWN)) {
            //如果之前的状态还没有 start，为init或者shutdown，0 - init, 1 - started, 2 - shut down
            // workerState can be 0 or 2 at this moment - let it always be 2.
            if (WORKER_STATE_UPDATER.getAndSet(this, WORKER_STATE_SHUTDOWN) != WORKER_STATE_SHUTDOWN) {
                //到达这一步只能是 之前的状态为init
                //如果之前的状态是 init，则清理被占用的 实例数 名额和 检测内存泄漏，之前为started状态的情况的清理工作在下面进行
                INSTANCE_COUNTER.decrementAndGet();
                if (leak != null) {
                    //检测 内存泄漏
                    boolean closed = leak.close(this);
                    assert closed;
                }
            }

            return Collections.emptySet();
        }

        // 如果来到了这里，说明 之前的状态是started
        try {
            boolean interrupted = false;
            // 循环 等待直到worker线程关闭
            while (workerThread.isAlive()) {
                workerThread.interrupt();
                try {
                    workerThread.join(100);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            //如果worker线程是被 interrupted调用的，也设置本线程的 interrupt状态
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            // 减少实例数
            INSTANCE_COUNTER.decrementAndGet();
            if (leak != null) {
                //检测内存泄漏
                boolean closed = leak.close(this);
                assert closed;
            }
        }
        // 返回还没执行的定时任务
        return worker.unprocessedTimeouts();
    }

    /**
     * @param task  定时任务
     * @param delay 延迟多久执行
     * @param unit
     * @return
     */
    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        // 基本的异常判断
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (unit == null) {
            throw new NullPointerException("unit");
        }
        // 增加等待执行的定时任务数
        long pendingTimeoutsCount = pendingTimeouts.incrementAndGet();

        // 如果超过最大就gg
        if (maxPendingTimeouts > 0 && pendingTimeoutsCount > maxPendingTimeouts) {
            pendingTimeouts.decrementAndGet();
            throw new RejectedExecutionException("Number of pending timeouts ("
                    + pendingTimeoutsCount + ") is greater than or equal to maximum allowed pending "
                    + "timeouts (" + maxPendingTimeouts + ")");
        }

        //如果 Worker线程没有启动，则启动
        start();

        // 计算这个任务的deadline，startTime是worker线程开始的时间戳
        // Add the timeout to the timeout queue which will be processed on the next tick.
        // During processing all the queued HashedWheelTimeouts will be added to the correct HashedWheelBucket.
        long deadline = System.nanoTime() + unit.toNanos(delay) - startTime;

        //溢出保护，则设置为 Long.MAX_VALUE的最大值
        // Guard against overflow.
        if (delay > 0 && deadline < 0) {
            deadline = Long.MAX_VALUE;
        }
        // 直接创建一个HashedWheelTimeout句柄
        HashedWheelTimeout timeout = new HashedWheelTimeout(this, task, deadline);
        // 添加到等待执行的任务队列中（大家还记得上一节我们说这是个MPSC队列么）
        timeouts.add(timeout);
        // 返回这个句柄
        return timeout;
    }

    /**
     * Returns the number of pending timeouts of this {@link Timer}.
     */
    public long pendingTimeouts() {
        return pendingTimeouts.get();
    }

    private static void reportTooManyInstances() {
        String resourceType = simpleClassName(HashedWheelTimer.class);
        logger.error("You are creating too many " + resourceType + " instances. " +
                resourceType + " is a shared resource that must be reused across the JVM," +
                "so that only a few instances are created.");
    }

    private final class Worker implements Runnable {
        //在本worker被shutdown时由于shutdown操作没有执行的timeout
        // (在 worker线程结束时 时间轮中的 剩余task和 新添加还没有进入时间轮 的task (不包括已经cancel的))
        private final Set<Timeout> unprocessedTimeouts = new HashSet<Timeout>();

        //当前的 tick
        private long tick;

        @Override
        public void run() {
            // 初始化startTime
            // Initialize the startTime.
            startTime = System.nanoTime();
            if (startTime == 0) {
                // We use 0 as an indicator for the uninitialized value here, so make sure it's not 0 when initialized.
                startTime = 1;
            }

            // 通知还等待在start方法上的线程，我worker初始化好了
            // Notify the other threads waiting for the initialization at start().
            startTimeInitialized.countDown();

            do {
                // 休眠直到下一个tick代表的时间到来
                //返回值: 如果本worker被 关闭，返回Long.MIN_VALUE,否则返回 从本worker线程开始的 tick时间
                final long deadline = waitForNextTick();
                if (deadline > 0) {
                    // 获取下一个bucket的index，即当前tick mod bucket数量
                    int idx = (int) (tick & mask);
                    // 处理掉已取消的任务
                    processCancelledTasks();
                    // 获取当前要处理的bucket
                    HashedWheelBucket bucket =
                            wheel[idx];
                    // 将待处理的任务移动到它该去的bucket去
                    transferTimeoutsToBuckets();
                    // 处理掉当前bucket的所有到期定时任务
                    bucket.expireTimeouts(deadline);
                    // 递增tick
                    tick++;
                }
                // 判断当前的状态是否started，如果是则进行下一个tick；否则退出
            } while (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_STARTED);

            // 到了这里，说明worker的状态已经不是started了，到了shutdown
            // 那就得善后了，遍历所有的bucket，将还没来得及处理的任务全部清理到unprocessedTimeouts中(包括在 时间轮中的和 还没有添加到时间轮中的timeout)
            // Fill the unprocessedTimeouts so we can return them from stop() method.
            for (HashedWheelBucket bucket : wheel) {
                bucket.clearTimeouts(unprocessedTimeouts);
            }
            // 遍历所有待处理并且还没添加到时间轮中的任务，添加到unprocessedTimeouts中
            for (; ; ) {
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    break;
                }
                if (!timeout.isCancelled()) {
                    unprocessedTimeouts.add(timeout);
                }
            }
            // 处理所有的已取消的task，防止内存泄漏
            // 其实我任务应该在结束的时候先处理已经取消的任务，这样似乎好理解一些
            // 不过我理解如果我这样做有可能出现的问题是bucket里面会有任务残留
            // 特别是这个间隙其他线程还在不断cancel任务，这里就不过多展开了
            processCancelledTasks();
        }

        private void transferTimeoutsToBuckets() {
            // todo 最多一次转移100000个待分发定时任务到它们对应的bucket内
            // 不然如果有一个线程一直添加定时任务就能让工作线程活生生饿死
            // transfer only max. 100000 timeouts per tick to prevent a thread to stale the workerThread when it just
            // adds new timeouts in a loop.
            for (int i = 0; i < 100000; i++) {
                // 获取一个定时任务
                HashedWheelTimeout timeout = timeouts.poll();
                if (timeout == null) {
                    // all processed
                    break;
                }
                // 如果已经取消就不管了
                if (timeout.state() == HashedWheelTimeout.ST_CANCELLED) {
                    // Was cancelled in the meantime.
                    continue;
                }

                // 计算从worker线程开始运行算起要经过多少个tick才能到这个任务
                long calculated = timeout.deadline / tickDuration;
                // 计算这个任务要经过多少圈
                timeout.remainingRounds = (calculated - tick) / wheel.length;
                // todo 如果这个任务我们取晚了，那就让他在这个tick执行
                final long ticks = Math.max(calculated, tick); // Ensure we don't schedule for past.
                // 计算他应该到的bucket的index
                int stopIndex = (int) (ticks & mask);

                // 指派过去即可
                HashedWheelBucket bucket = wheel[stopIndex];
                bucket.addTimeout(timeout);
            }
        }

        private void processCancelledTasks() {
            for (; ; ) {
                // 毕竟取消的任务占极少数，所以这里就没有个数限制了
                // 取出一个取消的任务
                HashedWheelTimeout timeout = cancelledTimeouts.poll();

                if (timeout == null) {
                    // all processed
                    break;
                }
                try {
                    // 这里实际上是将它从它所属的bucket的双向链表中删除，这里后面会看到
                    timeout.remove();
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("An exception was thrown while process a cancellation task", t);
                    }
                }
            }
        }

        /**
         * calculate goal nanoTime from startTime and current tick number,
         * then wait until that goal has been reached.
         * 计算从startTime开始的当前tick的时间数，然后等待 sleep到那个时间
         *  如果接收到关闭的请求，则返回 Long.MIN_VALUE，否则返回对应的时间
         * @return Long.MIN_VALUE if received a shutdown request,
         * current time otherwise (with Long.MIN_VALUE changed by +1)
         */
        private long waitForNextTick() {
            // 计算下一个tick的deadline
            long deadline = tickDuration * (tick + 1);

            // 循环直到当前时间来到了下一个tick
            for (; ; ) {
                // 计算当前时间
                final long currentTime = System.nanoTime() - startTime;
                // 计算需要sleep的毫秒数
                long sleepTimeMs = (deadline - currentTime + 999999) / 1000000;

                // 如果sleep的毫秒数小于等于0
                if (sleepTimeMs <= 0) {
                    // 特殊判断 这里说实话我没咋看懂
                    if (currentTime == Long.MIN_VALUE) {
                        return -Long.MAX_VALUE;
                    } else {
                        return currentTime;
                    }
                }

                // Check if we run on windows, as if thats the case we will need
                // to round the sleepTime as workaround for a bug that only affect
                // the JVM if it runs on windows.
                // 处理一些windows才有的jvm bug，sleep时间调整为 10ms的整数倍
                // See https://github.com/netty/netty/issues/356
                //就算sleep 0秒，还是会循环直到 时间到达 deadline
                if (PlatformDependent.isWindows()) {
                    sleepTimeMs = sleepTimeMs / 10 * 10;
                }

                // 尝试sleep到下个tick的deadline
                try {
                    Thread.sleep(sleepTimeMs);
                } catch (InterruptedException ignored) {
                    //如果 worker线程被关闭，返回Long.MIN_VALUE
                    if (WORKER_STATE_UPDATER.get(HashedWheelTimer.this) == WORKER_STATE_SHUTDOWN) {
                        return Long.MIN_VALUE;
                    }
                }
            }
        }

        public Set<Timeout> unprocessedTimeouts() {
            return Collections.unmodifiableSet(unprocessedTimeouts);
        }
    }

    private static final class HashedWheelTimeout implements Timeout {
        //未执行
        private static final int ST_INIT = 0;
        //被取消
        private static final int ST_CANCELLED = 1;
        //已执行
        private static final int ST_EXPIRED = 2;
        //状态原子更新
        private static final AtomicIntegerFieldUpdater<HashedWheelTimeout> STATE_UPDATER =
                AtomicIntegerFieldUpdater.newUpdater(HashedWheelTimeout.class, "state");

        //向关联的定时器
        private final HashedWheelTimer timer;
        //相关联的任务
        private final TimerTask task;
        //执行时间，本任务什么时候开始执行
        private final long deadline;

        //状态
        @SuppressWarnings({"unused", "FieldMayBeFinal", "RedundantFieldInitialization"})
        private volatile int state = ST_INIT;

        //在时间轮中的 round，当为0并且 到达本timeout对应的格子时 任务会被执行
        // remainingRounds will be calculated and set by Worker.transferTimeoutsToBuckets() before the
        // HashedWheelTimeout will be added to the correct HashedWheelBucket.
        long remainingRounds;

        //timeout双向链表中的上一个timeout，因为只有一个进程执行，不需要 synchronization / volatile
        // This will be used to chain timeouts in HashedWheelTimerBucket via a double-linked-list.
        // As only the workerThread will act on it there is no need for synchronization / volatile.
        HashedWheelTimeout next;
        //timeout双向链表中的下一个timeout，因为只有一个进程执行，不需要 synchronization / volatile
        HashedWheelTimeout prev;

        //本timeout对应的时间轮的格子
        // The bucket to which the timeout was added
        HashedWheelBucket bucket;

        HashedWheelTimeout(HashedWheelTimer timer, TimerTask task, long deadline) {
            this.timer = timer;
            this.task = task;
            this.deadline = deadline;
        }

        @Override
        public Timer timer() {
            return timer;
        }

        @Override
        public TimerTask task() {
            return task;
        }

        @Override
        public boolean cancel() {
            //如果本timeout已经被取消
            // only update the state it will be removed from HashedWheelBucket on next tick.
            if (!compareAndSetState(ST_INIT, ST_CANCELLED)) {
                return false;
            }
            // If a task should be canceled we put this to another queue which will be processed on each tick.
            // So this means that we will have a GC latency of max. 1 tick duration which is good enough. This way
            // we can make again use of our MpscLinkedQueue and so minimize the locking / overhead as much as possible.
            timer.cancelledTimeouts.add(this);
            return true;
        }

        void remove() {
            HashedWheelBucket bucket = this.bucket;
            if (bucket != null) {
                //如果已经进入时间轮
                bucket.remove(this);
            } else {
                //如果还没有进入时间轮
                timer.pendingTimeouts.decrementAndGet();
            }
        }

        public boolean compareAndSetState(int expected, int state) {
            return STATE_UPDATER.compareAndSet(this, expected, state);
        }

        public int state() {
            return state;
        }

        @Override
        public boolean isCancelled() {
            return state() == ST_CANCELLED;
        }

        @Override
        public boolean isExpired() {
            return state() == ST_EXPIRED;
        }

        //执行具体的业务逻辑
        public void expire() {
            if (!compareAndSetState(ST_INIT, ST_EXPIRED)) {
                return;
            }

            try {
                //task的run方法一定慎重放置任何有耗时可能的操作，不然就会导致
                // HashedWheelTimer中worker线程被长时间占用，其他任务得不到执行或者无法准时执行，最终导致性能和正确性下降
                task.run(this);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn("An exception was thrown by " + TimerTask.class.getSimpleName() + '.', t);
                }
            }
        }

        @Override
        public String toString() {
            final long currentTime = System.nanoTime();
            long remaining = deadline - currentTime + timer.startTime;

            StringBuilder buf = new StringBuilder(192)
                    .append(simpleClassName(this))
                    .append('(')
                    .append("deadline: ");
            if (remaining > 0) {
                buf.append(remaining)
                        .append(" ns later");
            } else if (remaining < 0) {
                buf.append(-remaining)
                        .append(" ns ago");
            } else {
                buf.append("now");
            }

            if (isCancelled()) {
                buf.append(", cancelled");
            }

            return buf.append(", task: ")
                    .append(task())
                    .append(')')
                    .toString();
        }
    }

    /**
     * Bucket that stores HashedWheelTimeouts. These are stored in a linked-list like datastructure to allow easy
     * removal of HashedWheelTimeouts in the middle. Also the HashedWheelTimeout act as nodes themself and so no
     * extra object creation is needed.
     */
    private static final class HashedWheelBucket {
        // Used for the linked-list datastructure
        // timeout双向链表的头节点
        private HashedWheelTimeout head;
        // timeout双向链表的尾节点
        private HashedWheelTimeout tail;

        /**
         * Add {@link HashedWheelTimeout} to this bucket.
         */
        public void addTimeout(HashedWheelTimeout timeout) {
            assert timeout.bucket == null;
            timeout.bucket = this;
            if (head == null) {
                head = tail = timeout;
            } else {
                tail.next = timeout;
                timeout.prev = tail;
                tail = timeout;
            }
        }

        /**
         * Expire all {@link HashedWheelTimeout}s for the given {@code deadline}.
         */
        //遍历整个链表，将应该执行的执行掉，然后被取消的从链表中移除，保证后续的执行。
        public void expireTimeouts(long deadline) {
            // 获取双向链表的头
            HashedWheelTimeout timeout = head;

            // 从头到尾处理所有的任务
            // process all timeouts
            while (timeout != null) {
                HashedWheelTimeout next = timeout.next;
                // 如果剩余轮数小于0 说明需要马上执行
                if (timeout.remainingRounds <= 0) {
                    // 将它从当前链表中移除
                    next = remove(timeout);
                    if (timeout.deadline <= deadline) {
                        //如果 round小于0，deadline肯定也小于deadline，否则抛出异常
                        // 执行timeout的逻辑
                        timeout.expire();
                    } else {
                        // The timeout was placed into a wrong slot. This should never happen.
                        throw new IllegalStateException(String.format(
                                "timeout.deadline (%d) > deadline (%d)", timeout.deadline, deadline));
                    }
                } else if (timeout.isCancelled()) {
                    // 如果已经被取消，同样remove掉
                    next = remove(timeout);
                } else {
                    // 否则将他们的轮数减一
                    timeout.remainingRounds--;
                }
                // 继续链表的遍历
                timeout = next;
            }
        }

        //将timeout从双向链表中移除
        public HashedWheelTimeout remove(HashedWheelTimeout timeout) {
            HashedWheelTimeout next = timeout.next;
            // remove timeout that was either processed or cancelled by updating the linked-list
            if (timeout.prev != null) {
                timeout.prev.next = next;
            }
            if (timeout.next != null) {
                timeout.next.prev = timeout.prev;
            }

            if (timeout == head) {
                // if timeout is also the tail we need to adjust the entry too
                if (timeout == tail) {
                    tail = null;
                    head = null;
                } else {
                    head = next;
                }
            } else if (timeout == tail) {
                // if the timeout is the tail modify the tail to be the prev node.
                tail = timeout.prev;
            }
            // null out prev, next and bucket to allow for GC.
            timeout.prev = null;
            timeout.next = null;
            timeout.bucket = null;
            timeout.timer.pendingTimeouts.decrementAndGet();
            return next;
        }

        /**
         * Clear this bucket and return all not expired / cancelled {@link Timeout}s.
         */
        //获取所有 没有执行并且没有cancel的timeout
        public void clearTimeouts(Set<Timeout> set) {
            for (; ; ) {
                HashedWheelTimeout timeout = pollTimeout();
                if (timeout == null) {
                    return;
                }
                if (timeout.isExpired() || timeout.isCancelled()) {
                    continue;
                }
                set.add(timeout);
            }
        }

        //从链表头取出timeout
        private HashedWheelTimeout pollTimeout() {
            HashedWheelTimeout head = this.head;
            if (head == null) {
                return null;
            }
            HashedWheelTimeout next = head.next;
            if (next == null) {
                tail = this.head = null;
            } else {
                this.head = next;
                next.prev = null;
            }

            // null out prev and next to allow for GC.
            head.next = null;
            head.prev = null;
            head.bucket = null;
            return head;
        }
    }
}
