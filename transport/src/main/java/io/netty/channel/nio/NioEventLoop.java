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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopException;
import io.netty.channel.SelectStrategy;
import io.netty.channel.SingleThreadEventLoop;
import io.netty.util.IntSupplier;
import io.netty.util.concurrent.RejectedExecutionHandler;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.ReflectionUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link SingleThreadEventLoop} implementation which register the {@link Channel}'s to a
 * {@link Selector} and so does the multi-plexing of these in the event loop.
 *
 */
public final class NioEventLoop extends SingleThreadEventLoop {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(NioEventLoop.class);

    private static final int CLEANUP_INTERVAL = 256; // XXX Hard-coded value, but won't need customization.

    private static final boolean DISABLE_KEYSET_OPTIMIZATION =
            SystemPropertyUtil.getBoolean("io.netty.noKeySetOptimization", false);

    private static final int MIN_PREMATURE_SELECTOR_RETURNS = 3;
    private static final int SELECTOR_AUTO_REBUILD_THRESHOLD;

    private final IntSupplier selectNowSupplier = new IntSupplier() {
        @Override
        public int get() throws Exception {
            return selectNow();
        }
    };
    private final Callable<Integer> pendingTasksCallable = new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
            return NioEventLoop.super.pendingTasks();
        }
    };

    // Workaround for JDK NIO bug.
    //
    // See:
    // - http://bugs.sun.com/view_bug.do?bug_id=6427854
    // - https://github.com/netty/netty/issues/203
    static {
        final String key = "sun.nio.ch.bugLevel";
        final String buglevel = SystemPropertyUtil.get(key);
        if (buglevel == null) {
            try {
                AccessController.doPrivileged(new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        System.setProperty(key, "");
                        return null;
                    }
                });
            } catch (final SecurityException e) {
                logger.debug("Unable to get/set System Property: " + key, e);
            }
        }

        int selectorAutoRebuildThreshold = SystemPropertyUtil.getInt("io.netty.selectorAutoRebuildThreshold", 512);
        if (selectorAutoRebuildThreshold < MIN_PREMATURE_SELECTOR_RETURNS) {
            selectorAutoRebuildThreshold = 0;
        }

        SELECTOR_AUTO_REBUILD_THRESHOLD = selectorAutoRebuildThreshold;

        if (logger.isDebugEnabled()) {
            logger.debug("-Dio.netty.noKeySetOptimization: {}", DISABLE_KEYSET_OPTIMIZATION);
            logger.debug("-Dio.netty.selectorAutoRebuildThreshold: {}", SELECTOR_AUTO_REBUILD_THRESHOLD);
        }
    }

    /**
     * The NIO {@link Selector}.
     */
    private Selector selector;
    private Selector unwrappedSelector;
    /**
     * selectedKeys是一个 SelectedSelectionKeySet 类对象，在NioEventLoop 的 openSelector 方法中创建，
     * 之后就通过反射将selectedKeys与 sun.nio.ch.SelectorImpl 中的用来存储select出来的key的集合的两个field绑定
     * 优化点:SelectedSelectionKeySet继承了set，替换了 SelectorImpl 中的用来存储select出来的key的集合的两个field的实现，
     *  SelectedSelectionKeySet 内部使用了数组，初始为1024，元素数量达到数组长度后双倍扩容，待程序跑过一段时间，
     *  等数组的长度足够长，每次在轮询到nio事件的时候，netty只需要O(1)的时间复杂度就能将 SelectionKey 塞到 set中去，
     *  而jdk底层使用的hashSet需要O(lgn)的时间复杂度。同时遍历的时候效率也会提高
     */
    private SelectedSelectionKeySet selectedKeys;

    private final SelectorProvider provider;

    /**
     * Boolean that controls determines if a blocked Selector.select should
     * break out of its selection process. In our case we use a timeout for
     * the select method and the select method will block for that time unless
     * waken up.
     */
    //表示需要唤醒阻塞的select操作，如果已经唤醒就必须要重复唤醒了，因为wakeup是一个昂贵的操作，则外部线程提交任务(即时和定时)时可以唤醒selector
    private final AtomicBoolean wakenUp = new AtomicBoolean();

    private final SelectStrategy selectStrategy;

    //该EventLoop中用于io的时间比例,默认50，和默认NioEventLoop的线程数量是CPU核数2倍对应
    private volatile int ioRatio = 50;
    private int cancelledKeys;
    private boolean needsToSelectAgain;

    NioEventLoop(NioEventLoopGroup parent, Executor executor, SelectorProvider selectorProvider,
                 SelectStrategy strategy, RejectedExecutionHandler rejectedExecutionHandler) {
        super(parent, executor, false, DEFAULT_MAX_PENDING_TASKS, rejectedExecutionHandler);
        if (selectorProvider == null) {
            throw new NullPointerException("selectorProvider");
        }
        if (strategy == null) {
            throw new NullPointerException("selectStrategy");
        }
        provider = selectorProvider;
        final SelectorTuple selectorTuple = openSelector();
        selector = selectorTuple.selector;
        unwrappedSelector = selectorTuple.unwrappedSelector;
        selectStrategy = strategy;
    }

    private static final class SelectorTuple {
        //jdk selector
        final Selector unwrappedSelector;
        //经过jdk优化过的 selector，如果没有经过jdk优化，则还是原来的jdk selector
        final Selector selector;

        SelectorTuple(Selector unwrappedSelector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = unwrappedSelector;
        }

        SelectorTuple(Selector unwrappedSelector, Selector selector) {
            this.unwrappedSelector = unwrappedSelector;
            this.selector = selector;
        }
    }

    private SelectorTuple openSelector() {
        final Selector unwrappedSelector;
        try {
            //没有经过处理的 selector
            unwrappedSelector = provider.openSelector();
        } catch (IOException e) {
            throw new ChannelException("failed to open a new selector", e);
        }

        if (DISABLE_KEYSET_OPTIMIZATION) {
            return new SelectorTuple(unwrappedSelector);
        }

        final SelectedSelectionKeySet selectedKeySet = new SelectedSelectionKeySet();

        Object maybeSelectorImplClass = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    return Class.forName(
                            "sun.nio.ch.SelectorImpl",
                            false,
                            PlatformDependent.getSystemClassLoader());
                } catch (Throwable cause) {
                    return cause;
                }
            }
        });

        if (!(maybeSelectorImplClass instanceof Class) ||
                // ensure the current selector implementation is what we can instrument.
                !((Class<?>) maybeSelectorImplClass).isAssignableFrom(unwrappedSelector.getClass())) {
            //如果没有 sun.nio.ch.SelectorImpl 类，打印异常
            if (maybeSelectorImplClass instanceof Throwable) {
                Throwable t = (Throwable) maybeSelectorImplClass;
                logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, t);
            }
            return new SelectorTuple(unwrappedSelector);
        }

        final Class<?> selectorImplClass = (Class<?>) maybeSelectorImplClass;

        Object maybeException = AccessController.doPrivileged(new PrivilegedAction<Object>() {
            @Override
            public Object run() {
                try {
                    Field selectedKeysField = selectorImplClass.getDeclaredField("selectedKeys");
                    Field publicSelectedKeysField = selectorImplClass.getDeclaredField("publicSelectedKeys");

                    Throwable cause = ReflectionUtil.trySetAccessible(selectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }
                    cause = ReflectionUtil.trySetAccessible(publicSelectedKeysField, true);
                    if (cause != null) {
                        return cause;
                    }

                    /**
                     * selectedKeys是一个 SelectedSelectionKeySet 类对象，
                     * 在NioEventLoop 的 openSelector 方法中创建，之后就通过反射将selectedKeys与 sun.nio.ch.SelectorImpl
                     * 中的两个field绑定
                     * 到sun.nio.ch.SelectorImpl 中我们可以看到，这两个field其实是两个HashSet，
                     * SelectedSelectionKeySet 实现了Set接口，但是内部实现使用了 数组
                     *
                     * todo netty自定义的SelectedSelectionKeySet在add方法进行了优化，底层使用数组
                     */
                    selectedKeysField.set(unwrappedSelector, selectedKeySet);
                    publicSelectedKeysField.set(unwrappedSelector, selectedKeySet);
                    return null;
                } catch (NoSuchFieldException e) {
                    return e;
                } catch (IllegalAccessException e) {
                    return e;
                }
            }
        });

        if (maybeException instanceof Exception) {
            selectedKeys = null;
            Exception e = (Exception) maybeException;
            logger.trace("failed to instrument a special java.util.Set into: {}", unwrappedSelector, e);
            return new SelectorTuple(unwrappedSelector);
        }
        selectedKeys = selectedKeySet;
        logger.trace("instrumented a special java.util.Set into: {}", unwrappedSelector);
        return new SelectorTuple(unwrappedSelector,
                                 new SelectedSelectionKeySetSelector(unwrappedSelector, selectedKeySet));
    }

    /**
     * Returns the {@link SelectorProvider} used by this {@link NioEventLoop} to obtain the {@link Selector}.
     */
    public SelectorProvider selectorProvider() {
        return provider;
    }

    @Override
    protected Queue<Runnable> newTaskQueue(int maxPendingTasks) {
        // This event loop never calls takeTask()
        //multi producers signle consumer
        /*
        默认是mpsc队列，mpsc队列，即多生产者单消费者队列，netty使用mpsc，方便的将外部线程的task聚集，在reactor线程
        内部用单线程来串行执行，我们可以借鉴netty的任务执行模式来处理类似多线程数据上报，定时聚合的应用
         */
        return maxPendingTasks == Integer.MAX_VALUE ? PlatformDependent.<Runnable>newMpscQueue()
                                                    : PlatformDependent.<Runnable>newMpscQueue(maxPendingTasks);
    }

    @Override
    public int pendingTasks() {
        // As we use a MpscQueue we need to ensure pendingTasks() is only executed from within the EventLoop as
        // otherwise we may see unexpected behavior (as size() is only allowed to be called by a single consumer).
        // See https://github.com/netty/netty/issues/5297
        if (inEventLoop()) {
            return super.pendingTasks();
        } else {
            return submit(pendingTasksCallable).syncUninterruptibly().getNow();
        }
    }

    /**
     * Registers an arbitrary {@link SelectableChannel}, not necessarily created by Netty, to the {@link Selector}
     * of this event loop.  Once the specified {@link SelectableChannel} is registered, the specified {@code task} will
     * be executed by this event loop when the {@link SelectableChannel} is ready.
     */
    public void register(final SelectableChannel ch, final int interestOps, final NioTask<?> task) {
        if (ch == null) {
            throw new NullPointerException("ch");
        }
        if (interestOps == 0) {
            throw new IllegalArgumentException("interestOps must be non-zero.");
        }
        if ((interestOps & ~ch.validOps()) != 0) {
            throw new IllegalArgumentException(
                    "invalid interestOps: " + interestOps + "(validOps: " + ch.validOps() + ')');
        }
        if (task == null) {
            throw new NullPointerException("task");
        }

        if (isShutdown()) {
            throw new IllegalStateException("event loop shut down");
        }

        try {
            ch.register(selector, interestOps, task);
        } catch (Exception e) {
            throw new EventLoopException("failed to register a channel", e);
        }
    }

    /**
     * Returns the percentage of the desired amount of time spent for I/O in the event loop.
     */
    public int getIoRatio() {
        return ioRatio;
    }

    /**
     * Sets the percentage of the desired amount of time spent for I/O in the event loop.  The default value is
     * {@code 50}, which means the event loop will try to spend the same amount of time for I/O as for non-I/O tasks.
     */
    public void setIoRatio(int ioRatio) {
        if (ioRatio <= 0 || ioRatio > 100) {
            throw new IllegalArgumentException("ioRatio: " + ioRatio + " (expected: 0 < ioRatio <= 100)");
        }
        this.ioRatio = ioRatio;
    }

    /**
     * Replaces the current {@link Selector} of this event loop with newly created {@link Selector}s to work
     * around the infamous epoll 100% CPU bug.
     */
    public void rebuildSelector() {
        if (!inEventLoop()) {
            execute(new Runnable() {
                @Override
                public void run() {
                    rebuildSelector0();
                }
            });
            return;
        }
        rebuildSelector0();
    }

    //new一个新的selector，将之前注册到老的selector上的的channel重新转移到新的selector上。
    private void rebuildSelector0() {
        final Selector oldSelector = selector;
        final SelectorTuple newSelectorTuple;

        if (oldSelector == null) {
            return;
        }

        try {
            //通过openSelector()方法创建一个新的selector
            newSelectorTuple = openSelector();
        } catch (Exception e) {
            logger.warn("Failed to create a new Selector.", e);
            return;
        }

        // Register all channels to the new Selector.
        int nChannels = 0;
        //执行一个死循环
        for (SelectionKey key: oldSelector.keys()) {//拿到有效的key
            Object a = key.attachment();
            try {
                //如果key 已经失效或者 已经注册到新的select上
                if (!key.isValid() || key.channel().keyFor(newSelectorTuple.unwrappedSelector) != null) {
                    continue;
                }

                int interestOps = key.interestOps();
                //取消该key在旧的selector上的事件注册
                key.cancel();
                //将该key对应的channel注册到新的selector上
                SelectionKey newKey = key.channel().register(newSelectorTuple.unwrappedSelector, interestOps, a);
                //重新绑定channel和新的key的关系
                if (a instanceof AbstractNioChannel) {
                    // Update SelectionKey
                    ((AbstractNioChannel) a).selectionKey = newKey;
                }
                nChannels ++;
            } catch (Exception e) {
                //只要执行过程中出现过一次并发修改selectionKeys异常，就重新开始转移
                logger.warn("Failed to re-register a Channel to the new Selector.", e);
                if (a instanceof AbstractNioChannel) {
                    //关闭channel
                    //会将关闭channel的消息 调用fireExceptionCaught()通知到调用方
                    AbstractNioChannel ch = (AbstractNioChannel) a;
                    ch.unsafe().close(ch.unsafe().voidPromise());
                } else {
                    @SuppressWarnings("unchecked")
                    NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                    invokeChannelUnregistered(task, key, e);
                }
            }
        }

        selector = newSelectorTuple.selector;
        unwrappedSelector = newSelectorTuple.unwrappedSelector;

        try {
            // time to close the old selector as everything else is registered to the new one
            oldSelector.close();
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("Failed to close the old Selector.", t);
            }
        }

        logger.info("Migrated " + nChannels + " channel(s) to the new Selector.");
    }

    @Override
    protected void run() {
        for (;;) {
            try {
                switch (selectStrategy.calculateStrategy(selectNowSupplier, hasTasks())) {
                    case SelectStrategy.CONTINUE:
                    continue;
                    case SelectStrategy.SELECT:
                        //1.首先轮询注册到reactor线程对用的selector上的所有的channel的IO事件
                        //wakenUp 表示当前select操作是否是唤醒状态，如果不是唤醒状态，则外部线程提交任务时可以唤醒selector
                        // 可以看到netty在进行一次新的loop之前，都会将wakeUp 被设置成false(可以让外部进程唤醒select操作)，标志新的一轮loop的开始，
                        select(wakenUp.getAndSet(false));

                        // 'wakenUp.compareAndSet(false, true)' is always evaluated
                        // before calling 'selector.wakeup()' to reduce the wake-up
                        // overhead. (Selector.wakeup() is an expensive operation.)
                        // 在调用'selector.wakeup（）'之前总是会评估'wake Up.compareAndSet（false，true）'以减少唤醒开销。
                        // （Selector.wakeup() 是一项昂贵的操作。）
                        // However, there is a race condition in this approach.
                        // The race condition is triggered when 'wakenUp' is set to
                        // true too early.
                        // 但是，这种方法存在竞争条件。 当'wakenUp'太早设置为true时会触发竞争条件。
                        // 'wakenUp' is set to true too early if:
                        // 1) Selector is waken up between 'wakenUp.set(false)' and
                        //    'selector.select(...)'. (BAD)
                        // 2) Selector is waken up between 'selector.select(...)' and
                        //    'if (wakenUp.get()) { ... }'. (OK)
                        //wakenUp设置过早有两种情况(上一个select方法)：
                        // 1. 在 wakenUp.set(false)和 'selector.select(...)之间设置（BAD）
                        // 2.在 selector.select(...)和 wakenUp.get()之间(OK)
                        // In the first case, 'wakenUp' is set to true and the
                        // following 'selector.select(...)' will wake up immediately.
                        // Until 'wakenUp' is set to false again in the next round,
                        // 'wakenUp.compareAndSet(false, true)' will fail, and therefore
                        // any attempt to wake up the Selector will fail, too, causing
                        // the following 'selector.select(...)' call to block
                        // unnecessarily.
                        // 在第一种情况下，'wakenUp'设置为true，后面调用的'selector.select（...）'将立即被唤醒(因为将wakenUp设置为true伴随着一次wakeup，如果wakeup当时没有select，那么下一次select会立即返回)
                        // 直到'wakenUp'在下一轮再次设置为false。
                        // 在这期间任何 'wakenUp.compareAndSet（false，true）'都将失败，从而导致所有selector唤醒操作都会失败
                        // To fix this problem, we wake up the selector again if wakenUp
                        // is true immediately after selector.select(...).
                        // It is inefficient in that it wakes up the selector for both
                        // the first case (BAD - wake-up required) and the second case
                        // (OK - no wake-up required).
                        // 要解决这个问题，如果在selector.select（...）之后判断到wakenUp为true，我们会立即再次唤醒选择器。 它是低效的，
                        // 因为它同时唤醒了第一种情况（BAD - 需要唤醒）和第二种情况（OK - 不需要唤醒）的选择器。
                        if (wakenUp.get()) {
                            selector.wakeup();
                        }
                        // fall through
                    default:
                }

                cancelledKeys = 0;
                needsToSelectAgain = false;
                final int ioRatio = this.ioRatio;
                //如果io占比为100(处理完io后会处理所有可以处理的即时任务和定时任务)
                if (ioRatio == 100) {
                    try {
                       //2.处理产生网络IO事件的channel
                        /*
                        netty的reactor线程第二步做的事情为处理IO事件，
                        netty使用数组替换掉jdk原生的HashSet来保证IO事件的高效处理，
                        每个SelectionKey上绑定了netty类AbstractChannel对象作为attachment，
                        在处理每个SelectionKey的时候，就可以找到AbstractChannel，
                        然后通过pipeline的方式将处理串行到ChannelHandler，回调到用户方法
                         */
                        processSelectedKeys();
                    } finally {
                        //3.处理任务队列
                        // Ensure we always run tasks.
                        runAllTasks();
                    }
                } else {
                    //netty在处理io之前记录了一下io操作的开始时间
                    final long ioStartTime = System.nanoTime();
                    try {
                        processSelectedKeys();
                    } finally {
                        // Ensure we always run tasks.
                        //然后在io结束的时候计算了一下这段io操作花的总的时间 ioTime
                        final long ioTime = System.nanoTime() - ioStartTime;
                        runAllTasks(ioTime * (100 - ioRatio) / ioRatio);
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
            // Always handle shutdown even if the loop processing threw an exception.
            try {
                if (isShuttingDown()) {
                    closeAll();
                    if (confirmShutdown()) {
                        return;
                    }
                }
            } catch (Throwable t) {
                handleLoopException(t);
            }
        }
    }

    private static void handleLoopException(Throwable t) {
        logger.warn("Unexpected exception in the selector loop.", t);

        // Prevent possible consecutive immediate failures that lead to
        // excessive CPU consumption.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // Ignore.
        }
    }

    private void processSelectedKeys() {
        if (selectedKeys != null) {
            //selectedKeys不为空，表示使用优化过的selectedKeys
            processSelectedKeysOptimized();
        } else {
            //正常的处理
            processSelectedKeysPlain(selector.selectedKeys());
        }
    }

    @Override
    protected void cleanup() {
        try {
            selector.close();
        } catch (IOException e) {
            logger.warn("Failed to close a selector.", e);
        }
    }

    void cancel(SelectionKey key) {
        key.cancel();
        cancelledKeys ++;
        if (cancelledKeys >= CLEANUP_INTERVAL) {
            cancelledKeys = 0;
            needsToSelectAgain = true;
        }
    }

    @Override
    protected Runnable pollTask() {
        Runnable task = super.pollTask();
        if (needsToSelectAgain) {
            selectAgain();
        }
        return task;
    }

    private void processSelectedKeysPlain(Set<SelectionKey> selectedKeys) {
        // check if the set is empty and if so just return to not create garbage by
        // creating a new Iterator every time even if there is nothing to process.
        // See https://github.com/netty/netty/issues/597
        if (selectedKeys.isEmpty()) {
            return;
        }

        Iterator<SelectionKey> i = selectedKeys.iterator();
        for (;;) {
            final SelectionKey k = i.next();
            final Object a = k.attachment();
            //todo 这边把SelectionKey移除了
            i.remove();

            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }

            if (!i.hasNext()) {
                break;
            }

            if (needsToSelectAgain) {
                selectAgain();
                selectedKeys = selector.selectedKeys();

                // Create the iterator again to avoid ConcurrentModificationException
                if (selectedKeys.isEmpty()) {
                    break;
                } else {
                    i = selectedKeys.iterator();
                }
            }
        }
    }

    private void processSelectedKeysOptimized() {
        for (int i = 0; i < selectedKeys.size; ++i) {
            // 1.取出IO事件以及对应的channel
            final SelectionKey k = selectedKeys.keys[i];
            // null out entry in the array to allow to have it GC'ed once the Channel close
            // See https://github.com/netty/netty/issues/2363
            //从selector中获取到触发io的selectKey后，将selectedKeys[i]置为null。
            /*
            todo 想象一下这种场景，假设一个NioEventLoop平均每次轮询出N个IO事件，
            高峰期轮询出3N个事件，那么selectedKeys的物理长度要大于等于3N，
            如果每次处理这些key，不置selectedKeys[i]为空，那么高峰期一过，
            这些保存在数组尾部的selectedKeys[i]对应的SelectionKey将一直无法被回收，
            SelectionKey对应的对象可能不大，但是要知道，它可是有attachment的，
            有一点我们必须清楚，attachment可能很大，这样一来，这些元素是GC root可达的，
            很容易造成gc不掉，内存泄漏就发生了。(和attachment关系不大，因为移除selectKey并不会移除attachment，
            因为attachment是在之前注册interestOps的时候绑定的，
            但是内存泄漏的问题是需要考虑的)
             */
            selectedKeys.keys[i] = null;

            final Object a = k.attachment();
            // 2.处理该channel
            /*
            通过 AbstractNioChannel 的doRegister方法 将channel注册到 eventloop对应的selector上时
            将attachment设置为 对应的 AbstractNioChannel实例：
             javaChannel().register(eventLoop().unwrappedSelector(), 0, this)。
            这样在jdk轮询出某条SelectableChannel有IO事件发生时，就可以直接取出AbstractNioChannel进行后续操作
             */
            if (a instanceof AbstractNioChannel) {
                processSelectedKey(k, (AbstractNioChannel) a);
            } else {
                /*
                说明注册到selctor上的attachment还有另外一中类型，就是 NioTask，
                NioTask主要是用于当一个 SelectableChannel 注册到selector的时候，执行一些任务
                 */
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                processSelectedKey(k, task);
            }
            // 3.判断是否该再来次轮询
            //每一轮循环，每取消256个SelectionKey时，重新清理一下selectionKey，保证现存的SelectionKey及时有效
            /*
            在channel从selector上移除的时候，调用cancel函数将key取消，并且当被去掉的key到达 CLEANUP_INTERVAL 的时候，
            设置needsToSelectAgain为true,CLEANUP_INTERVAL默认值为256,对于每个NioEventLoop而言，每一轮循环，每256个channel从
            selector上移除的时候，就标记 needsToSelectAgain 为true
            //重置 selector中选择出来的触发io操作的selectionKey集合(清除已经存在的触发io操作的selectionKey集合，
            并重新调用selector.selectNow填充)，并重新开始遍历新的selectionKey集合
             */
            if (needsToSelectAgain) {
                // null out entries in the array to allow to have it GC'ed once the Channel close
                // See https://github.com/netty/netty/issues/2363
                /*
                每256个selectionKey被取消 ，就会进入到if的代码块，首先，将selectedKeys的内部数组全部清空，
                方便被jvm垃圾回收，然后重新调用selectAgain重新填装一下 selectionKey
                 */
                selectedKeys.reset(i + 1);

                //重新选择出 准备好io操作的selectionKey(之前选择出来但是没有处理就被清除的也会再次被选择出来)
                selectAgain();
                i = -1;
            }
        }
    }

    /**
     * 1.对于boss NioEventLoop来说，轮询到的是基本上就是accept事件，后续的事情
     * 就通过他的pipeline将连接交给ServerBootstrapAcceptor这个ChannelInboundHandler处理，
     * ServerBootstrapAcceptor将会从传入的 childGroup(EventLoopGroup)选择一个nioEventLoop进行注册，
     * 同时之后所有关闭这个channel的数据处理都交给这个EventLoop来进行处理
     2.轮询到的基本上都是io读写事件，后续的事情就是通过他的pipeline将读取到的字节流传递给
     每个channelHandler来处理(所有数据处理操作都在分配到的EventLoop中进行处理)
     * @param k
     * @param ch
     */
    private void processSelectedKey(SelectionKey k, AbstractNioChannel ch) {
        final AbstractNioChannel.NioUnsafe unsafe = ch.unsafe();
        if (!k.isValid()) {
            //如果 SelectionKey 已经被取消，则关闭对应的 channel(todo 注册到多个selector上时，从本selector关闭不应该关闭通道，但是当前没有这种场景，一个channel只会被注册到一个selector上)
            final EventLoop eventLoop;
            try {
                eventLoop = ch.eventLoop();
            } catch (Throwable ignored) {
                // If the channel implementation throws an exception because there is no event loop, we ignore this
                // because we are only trying to determine if ch is registered to this event loop and thus has authority
                // to close ch.
                return;
            }
            // Only close ch if ch is still registered to this EventLoop. ch could have deregistered from the event loop
            // and thus the SelectionKey could be cancelled as part of the deregistration process, but the channel is
            // still healthy and should not be closed.
            // See https://github.com/netty/netty/issues/5125
            if (eventLoop != this || eventLoop == null) {
                return;
            }
            // close the channel if the key is not valid anymore
            unsafe.close(unsafe.voidPromise());
            return;
        }

        try {
            int readyOps = k.readyOps();
            // We first need to call finishConnect() before try to trigger a read(...) or write(...) as otherwise
            // the NIO JDK channel implementation may throw a NotYetConnectedException.
            if ((readyOps & SelectionKey.OP_CONNECT) != 0) {
                // remove OP_CONNECT as otherwise Selector.select(..) will always return without blocking
                // See https://github.com/netty/netty/issues/924
                int ops = k.interestOps();
                ops &= ~SelectionKey.OP_CONNECT;
                k.interestOps(ops);

                //首先做connect的回调
                unsafe.finishConnect();
            }

            // Process OP_WRITE first as we may be able to write some queued buffers and so free memory.
            if ((readyOps & SelectionKey.OP_WRITE) != 0) {
                // Call forceFlush which will also take care of clear the OP_WRITE once there is nothing left to write
                ch.unsafe().forceFlush();
            }

            // Also check for readOps of 0 to workaround possible JDK bug which may otherwise lead
            // to a spin loop
            //boos reactor线程已经轮询到 SelectionKey.OP_ACCEPT 事件，说明有新的连接进入，此时将调用channel的
            // unsafe来进行实际的操作
            if ((readyOps & (SelectionKey.OP_READ | SelectionKey.OP_ACCEPT)) != 0 || readyOps == 0) {
                unsafe.read();
            }
        } catch (CancelledKeyException ignored) {
            unsafe.close(unsafe.voidPromise());
        }
    }

    private static void processSelectedKey(SelectionKey k, NioTask<SelectableChannel> task) {
        int state = 0;
        try {
            task.channelReady(k.channel(), k);
            state = 1;
        } catch (Exception e) {
            k.cancel();
            invokeChannelUnregistered(task, k, e);
            state = 2;
        } finally {
            switch (state) {
            case 0:
                k.cancel();
                invokeChannelUnregistered(task, k, null);
                break;
            case 1:
                if (!k.isValid()) { // Cancelled by channelReady()
                    invokeChannelUnregistered(task, k, null);
                }
                break;
            }
        }
    }

    private void closeAll() {
        selectAgain();
        Set<SelectionKey> keys = selector.keys();
        Collection<AbstractNioChannel> channels = new ArrayList<AbstractNioChannel>(keys.size());
        for (SelectionKey k: keys) {
            Object a = k.attachment();
            if (a instanceof AbstractNioChannel) {
                channels.add((AbstractNioChannel) a);
            } else {
                k.cancel();
                @SuppressWarnings("unchecked")
                NioTask<SelectableChannel> task = (NioTask<SelectableChannel>) a;
                invokeChannelUnregistered(task, k, null);
            }
        }

        for (AbstractNioChannel ch: channels) {
            ch.unsafe().close(ch.unsafe().voidPromise());
        }
    }

    private static void invokeChannelUnregistered(NioTask<SelectableChannel> task, SelectionKey k, Throwable cause) {
        try {
            task.channelUnregistered(k.channel(), cause);
        } catch (Exception e) {
            logger.warn("Unexpected exception while running NioTask.channelUnregistered()", e);
        }
    }

    @Override
    protected void wakeup(boolean inEventLoop) {
        //调用wakeup方法唤醒selector阻塞
        if (!inEventLoop && wakenUp.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    Selector unwrappedSelector() {
        return unwrappedSelector;
    }

    int selectNow() throws IOException {
        try {
            return selector.selectNow();
        } finally {
            // restore wakeup state if needed
            if (wakenUp.get()) {
                selector.wakeup();
            }
        }
    }

    /*
    不断地轮询是否有IO事件发生，并且在轮询的过程中不断检查是否有定时任务和普通任务，保证了netty的
    任务队列中的任务得到有效执行，轮询过程顺带用一个计数器避开了了jdk空轮询的bug，
     */
    private void select(boolean oldWakenUp) throws IOException {
        Selector selector = this.selector;
        try {
            //1. 定时 任务截止事时间快到了，中断本次轮询
            int selectCnt = 0;
            long currentTimeNanos = System.nanoTime();
            /**
             * NioEventLoop中reactor线程的select操作也是一个for循环，
             * 在for循环第一步中，如果发现当前的定时任务队列中有任务的
             * 截止事件快到了(<=0.5ms)，就跳出循环。此外，跳出之前
             * 如果发现目前为止还没有进行过select操作（if (selectCnt == 0)），
             * 那么就调用一次selectNow()，该方法会立即返回，不会阻塞
             */
            //获取最近的 定时任务的开始时间(ns)(如果没有定时任务，则超时时间为1s)
            long selectDeadLineNanos = currentTimeNanos + delayNanos(currentTimeNanos);
            for (;;) {
                long timeoutMillis = (selectDeadLineNanos - currentTimeNanos + 500000L) / 1000000L;
                if (timeoutMillis <= 0) {
                    //如果小于0.5ms
                    if (selectCnt == 0) {
                        //跳出之前如果发现目前为止还没有进行过select操作（if (selectCnt == 0)），那么就调用一次selectNow()，该方法会立即返回，不会阻塞
                        selector.selectNow();
                        selectCnt = 1;
                    }
                    break;
                }

                // If a task was submitted when wakenUp value was true, the task didn't get a chance to call
                // Selector#wakeup. So we need to check task queue again before executing select operation.
                // If we don't, the task might be pended until select operation was timed out.
                // It might be pended until idle timeout if IdleStateHandler existed in pipeline.
                /**
                 如果在wakenUp值为true时提交了任务，则该任务没有机会调用Selector #wakeup。
                 因此我们需要在执行select操作之前再次检查任务队列。
                   如果我们不这样做，那么任务可能会被搁置，直到选择操作超时。
                   如果IdleStateHandler存在于channel中，它可能会被挂起直到空闲超时。
                 */
                //2.有需要立即执行的任务加入，中断本次轮询
                // 这边可能会有一个疑问：如果在 wakenUp.getAndSet(false) 和本行代码之间 有 execute操作，从而把 wakenUp设置为true的情况，不是就不能立即执行了么？
                //对于selector唤醒有如下知识点：
                //调用Selector对象的wakeup( )方法将使得选择器上的第一个还没有返回的选择操作立即返回。如果当前没有在进行中的选择，那么下一次对select( )方法的一种形式的调用将立即返回。
                //因此 这步没有处理task没有关系，在下面的select操作也不会阻塞，也会立即执行task
                if (hasTasks() && wakenUp.compareAndSet(false, true)) {
                    /*
                    为了保证任务队列能够及时执行，在进行阻塞select操作的时候会判断任务队列是否为空，如果不为空，就执行一次非阻塞select操作，跳出循环
                     */
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                //阻塞式select操作
                /**
                 * 执行到这一步，说明netty任务队列里面队列为空，并且所有定时任务延迟时间还未到(大于0.5ms)，
                 * 于是，在这里进行一次阻塞select操作，截止到第一个定时任务的截止时间
                 */
                int selectedKeys = selector.select(timeoutMillis);
                selectCnt ++;

                /**
                 * 阻塞select操作结束之后，netty又做了一系列的状态判断来决定是否中断本次轮询，中断本次轮询的条件有:
                 * 1. select到IO事件 （selectedKeys != 0）
                 2. oldWakenUp 参数为true(有可能是因为关闭本eventloop)
                 3.任务队列里面有任务（hasTasks）
                 4.第一个定时任务的开始时间小于当前时间，需要执行 （hasScheduledTasks（））
                 5.用户主动唤醒(有可能不是因为execute，也有可能是因为关闭本eventloop)（wakenUp.get()）
                 */
                if (selectedKeys != 0 || oldWakenUp || wakenUp.get() || hasTasks() || hasScheduledTasks()) {
                    // - Selected something,
                    // - waken up by user, or
                    // - the task queue has a pending task.
                    // - a scheduled task is ready for processing
                    break;
                }
                if (Thread.interrupted()) {
                    // Thread was interrupted so reset selected keys and break so we not run into a busy loop.
                    // As this is most likely a bug in the handler of the user or it's client library we will
                    // also log it.
                    //
                    // See https://github.com/netty/netty/issues/2426
                    if (logger.isDebugEnabled()) {
                        logger.debug("Selector.select() returned prematurely because " +
                                "Thread.currentThread().interrupt() was called. Use " +
                                "NioEventLoop.shutdownGracefully() to shutdown the NioEventLoop.");
                    }
                    selectCnt = 1;
                    break;
                }

                //nio selector bug解决
                /**
                 * 该bug会导致Selector一直空轮询，最终导致cpu 100%，nio server不可用，严格意义上来说，
                 * netty没有解决jdk的bug，而是通过重建selector来巧妙地避开了这个bug，具体做法如下
                 */
                long time = System.nanoTime();
                /**
                 * netty 会在每次进行 selector.select(timeoutMillis) 之前记录一下开始时间currentTimeNanos，
                 * 在select之后记录一下结束时间，判断select操作是否至少持续了timeoutMillis秒
                 */
                /**
                 * 如果持续的时间大于等于timeoutMillis，说明就是一次有效的轮询，重置selectCnt标志，否则，表明
                 * 该阻塞方法并没有阻塞这么长时间，可能触发了jdk的空轮询bug，当空轮询的次数超过一个阀值的时候，默认是512，就开始重建selector
                 */
                if (time - TimeUnit.MILLISECONDS.toNanos(timeoutMillis) >= currentTimeNanos) {
                    // timeoutMillis elapsed without anything selected.
                    //如果有一次的 select时间超过timeoutMillis，则重新开始计数
                    selectCnt = 1;
                } else if (SELECTOR_AUTO_REBUILD_THRESHOLD > 0 &&
                        selectCnt >= SELECTOR_AUTO_REBUILD_THRESHOLD) {
                    // The selector returned prematurely many times in a row.
                    // Rebuild the selector to work around the problem.
                    //如果不满足上面任何退出轮询的条件并且执行持续的时间小于timeoutMillis，但是执行到这边了，就说明发生了nio selector bug
                    //并空轮询
                    //当空轮询的次数超过一个阀值的时候，默认是512，就开始重建selector
                    logger.warn(
                            "Selector.select() returned prematurely {} times in a row; rebuilding Selector {}.",
                            selectCnt, selector);

                    //重建selector
                    rebuildSelector();
                    selector = this.selector;

                    // Select again to populate selectedKeys.
                    selector.selectNow();
                    selectCnt = 1;
                    break;
                }

                currentTimeNanos = time;
            }

            if (selectCnt > MIN_PREMATURE_SELECTOR_RETURNS) {
                //如果有超过三次 selector都是 提前返回(有可能发生了空轮询异常)，则打出warn日志
                if (logger.isDebugEnabled()) {
                    logger.debug("Selector.select() returned prematurely {} times in a row for Selector {}.",
                            selectCnt - 1, selector);
                }
            }
        } catch (CancelledKeyException e) {
            if (logger.isDebugEnabled()) {
                logger.debug(CancelledKeyException.class.getSimpleName() + " raised by a Selector {} - JDK bug?",
                        selector, e);
            }
            // Harmless exception - log anyway
        }
    }

    //重新调用selectAgain重新填装一下 selectionKey
    private void selectAgain() {
        needsToSelectAgain = false;
        try {
            selector.selectNow();
        } catch (Throwable t) {
            logger.warn("Failed to update SelectionKeys.", t);
        }
    }
}
