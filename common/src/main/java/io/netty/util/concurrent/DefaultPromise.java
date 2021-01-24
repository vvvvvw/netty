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
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    //netty内部自己对日志打印的实现，当然这也算是一个模块了后面会详细介绍只用知道这是打印日志使用的
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    //打印异常的日志对象
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    // 可以嵌套的Listener的最大层数，可见最大值为8
    //嵌套的Listener，是指在listener的operationComplete方法中，
    // 可以再次使用future.addListener()继续添加listener，Netty限制的最大层数是8,
    //大于8层，则不同步执行，将任务提交给 本promise拥有的 executor执行
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    //用来设置结果的更新器
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    private static final Object SUCCESS = new Object();
    private static final Object UNCANCELLABLE = new Object();
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));

    private volatile Object result;
    //用来作为 notifyListener的执行器
    private final EventExecutor executor;
    /**
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    //等待操作DefaultPromise监听的操作完成的线程数量
    private short waiters;

    /**
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    //构造器，传入执行器，并进行了校验如果执行器是null则会抛出nullpoint异常
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    //无参构造，如果子类的实现没有使用到执行器那么可以调用无参构造，因为executor是final的所以必须初始化这里默认给了null
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    ////前面说过Primise是个特殊的Future，可以进行手动设置执行成功
    @Override
    public Promise<V> setSuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    ////和上方方法并没有不同，仅仅是如果设置成功失败则返回false，而上方设置失败则抛出异常
    @Override
    public boolean trySuccess(V result) {
        if (setSuccess0(result)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    ////尝试设置当前任务为失败并且传入一个异常信息，返回true则尝试成功并且通知监听器，否则返回false
    @Override
    public Promise<V> setFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    // //尝试设置当前任务为失败并且传入一个异常信息，返回true则尝试成功并且通知监听器，否则返回false
    @Override
    public boolean tryFailure(Throwable cause) {
        if (setFailure0(cause)) {
            notifyListeners();
            return true;
        }
        return false;
    }

    ////设置当前任务为不可取消
    @Override
    public boolean setUncancellable() {
        //设置成功则返回true说明当前的任务状态已经是不可取消状态了
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        Object result = this.result;
        //否则获取当前的结果并且判断是成功了还是被取消了，两者一者满足即可。
        //1、要么成功2、要么被取消
        return !isDone0(result) || !isCancelled0(result);
    }

    //当前的任务是否执行完成
    @Override
    public boolean isSuccess() {
        Object result = this.result;
        //result不等于null是必须的因为初始值就是null说明并没有进行任何状态的设置
        //result不等于UNCANCELLABLE 代表是不可取消状态但是他是未完成的因为最终的result并不会是他，从而代表正在运行并且在运行途中还设置了不可取消状态
        //result 不是CauseHolder类型，之前在定义失败异常的时候就是使用这个类的对象创建的标记，从而代表结束运行但是是被取消的所以不能算是完成
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    //是否取消
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    //获取执行异常
    @Override
    public Throwable cause() {
        Object result = this.result;
        return (result instanceof CauseHolder) ? ((CauseHolder) result).cause : null;
    }

    //添加监听器
    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        //锁住当前对象
        synchronized (this) {
            //添加监听器
            addListener0(listener);
        }

        //是否完成了当前的任务，如果完成则进行通知
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    //添加多个监听器
    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        //锁住当前对象
        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        //如果任务执行成功则直接进行通知
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    //删除监听器
    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        //锁住当前对象
        synchronized (this) {
            //进行监听器的删除
            removeListener0(listener);
        }

        return this;
    }

    //同上 只不过监听器是多个并且进行的监听器的遍历去删除
    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    @Override
    public Promise<V> await() throws InterruptedException {
        //如果当前的任务已经执行完则返回this
        if (isDone()) {
            return this;
        }

        //定义的时候说过await如果发生了中断则会抛出异常，这里判断当前前程是否中断，如果中断则抛出异常
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        //检查是否死锁
        checkDeadLock();

        //当前线程锁住当前的代码块，其他线程不可访问
        synchronized (this) {
            //是否成功，如果并没有成功则进入该while，如果成功则返回this
            while (!isDone()) {
                //之前说过waiters字段用来记录等待的线程，此处是对waiters字段进行+1操作
                incWaiters();
                try {
                    //当前对象进行等待
                    wait();
                } finally {
                    //等待结束或者被唤醒则进行-1操作
                    decWaiters();
                }
            }
        }
        return this;
    }

    ////与上方方法解释相同只不过如果被中断了不会抛出异常，而是尝试中断当前的线程
    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                incWaiters();
                try {
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    ////await加强版，支持设置等到时长，这里讲传入的时长转换为了纳秒
    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    //传入的试毫秒转为纳秒
    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    //与上方方法相同只不过将抛出的中断异常转为了内部错误，在定义的时候就有说过此方法不会抛出中断异常
    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    //获取当前结果非阻塞，如果当前值是异常或者是SUCCESS或者UNCANCELLABLE则返回null否则返回当前值
    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS) {
            return null;
        }
        return (V) result;
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    ////取消当前任务执行，并且尝试中断，但是当前方法并没有尝试中断所以传参则无用
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        //设置当前result的值为CANCELLATION_CAUSE_HOLDER（取消异常）
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            //设置成功则检查并唤醒之前wait中等待的线程
            checkNotifyWaiters();
            //通知所有的监听器
            notifyListeners();
            return true;
        }
        //取消失败则返回false说明当前result已经被设置成其他的结果
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    //同步等待调用了之前wait方法。如果失败则尝试抛出异常
    @Override
    public Promise<V> sync() throws InterruptedException {
        await();
        rethrowIfFailed();
        return this;
    }

    //与上方方法一样只不过这里不会抛出中断异常
    @Override
    public Promise<V> syncUninterruptibly() {
        awaitUninterruptibly();
        rethrowIfFailed();
        return this;
    }

    //打印当前任务的状态
    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    //获取传入的执行器
    protected EventExecutor executor() {
        return executor;
    }

    //之前用到的检查死锁方法，就是
    // 检查当前调用方法的线程是不是执行器的线程如果是则说明发生了死锁需要抛出异常停止死锁操作
    //获取执行器，如果执行器为null则不会发生死锁，
    // 如果不是null则判断当前线程是否是执行器线程，
    protected void checkDeadLock() {
        EventExecutor e = executor();
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    //通知所有的监听器
    //eventExecutor 通知监听器的执行器
    //future 需要通知的任务
    //listener 需要通知的监听器
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        checkNotNull(eventExecutor, "eventExecutor");
        checkNotNull(future, "future");
        checkNotNull(listener, "listener");
        notifyListenerWithStackOverFlowProtection(eventExecutor, future, listener);
    }

    //通知监听器
    private void notifyListeners() {
        //获取当前任务的的执行器
        EventExecutor executor = executor();
        //如果调用这个方法的线程就是执行器的线程则进入该if
        if (executor.inEventLoop()) {
            //获取当前线程的InternalThreadLocalMap对象
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            //通过线程的数据对象或去到当前的任务监听器通知的层次，如果是第一次通知则为0
            final int stackDepth = threadLocals.futureListenerStackDepth();
            //嵌套的Listener，是指在listener的operationComplete方法中，
            // 可以再次使用future.addListener()继续添加listener，Netty限制的最大层数是8
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                //进入后再层次中+1
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    //并且立即通知
                    notifyListenersNow();
                } finally {
                    //如果通知完成则还原深度，可以理解为又进行了减一
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        //如果当前线程不是执行器或者当前的线程深度已经大于了设置的最大深度，则使用当前的执行器进行通知
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    //此方法和上方的方法相同但是上方的通知是使用当前任务的监听器而此处使用的是传入的监听器
    // 因为做了缓存上方代码是可以复用的。既然逻辑一样那么这里就不进行介绍了根据上方源码进行解读即可
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            //嵌套的Listener，是指在listener的operationComplete方法中，
            // 可以再次使用future.addListener()继续添加listener，Netty限制的最大层数是8
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    //此处与上方有差异，上方调用notifyListenersNow
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    //创建了方法内部的局部变量
    private void notifyListenersNow() {
        Object listeners;
        synchronized (this) {
            //使用this作为线程锁，并且上锁
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            // 如果当前任务并没有通知并且是有监听器的则进行接下来的逻辑，否则return。
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            //正在通知的状态
            notifyingListeners = true;
            //并且将当前内部属性赋值给刚才的局部变量
            listeners = this.listeners;
            //然后将内部属性设置为null，因为正在通知状态如果通知完成将会修改回来所以这里置为null则为了保证第二个条件成立
            this.listeners = null;
        }
        //循环调用进行通知
        for (;;) {
            ////这里对监听器做了两个处理第一个是当前监听器是一个列表代表多个监听器
            // 第二个则代表当前监听器是一个监听器，不一样的数据结构对应不一样的处理。
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            //通知完成后继续上锁
            synchronized (this) {
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    ////如果当前的监听器已经重置为null则设置正在通知的状态结束，
                    // 否则设置当前的局部变量为当前的监听器然后设置当前监听器为null
                    notifyingListeners = false;
                    return;
                }
                //可能会在通知的时候又有新的监听器进来所以这里再次设置了
                listeners = this.listeners;
                this.listeners = null;
            }
            //这里对此方法进行一个小结: 这里使用了两个地方用锁而且他们的锁是一样的所以会出现竞争问题，
            // 如果第一个线程进来并且设置为正在发送通知那么剩下的线程都不会再继续执行
            // 并且当前的监听器是null的 如果通过别的途径再次添加了监听器并且当前的通知还是正在通知的状态那么其他的线程还是进不来，
            // 但是当前的线程执行完通知会发现当前的监听器又发生了变化，
            // 那么这个for的死循环再次执行，因为发现又有新的通知所以当前还是正在发送通知状态，
            // 所以其他线程还是进不来，最终还是由当前线程进行执行。而在讲述notifyListenerWithStackOverFlowProtection的
            // 时候说过监听器发生改变所以不能复用的问题，
            // 而这里就处理如果当前的监听器发送改变的处理。
        }
    }

    //这里进行通知数组类型的监听器
    private void notifyListeners0(DefaultFutureListeners listeners) {
        //首先获取到传入监听器内部包含的数组
        GenericFutureListener<?>[] a = listeners.listeners();
        //然后进行遍历通知遍历中的监听器
        //而且要注意此方法是私有的那么就代表除了使用它可以进行遍历以外其他的继承只能一个一个发同通知，具体的看实现逻辑
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            l.operationComplete(future);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
        }
    }

    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners == null) {
            listeners = listener;
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    private boolean setValue0(Object objResult) {
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            checkNotifyWaiters();
            return true;
        }
        return false;
    }

    private synchronized void checkNotifyWaiters() {
        if (waiters > 0) {
            notifyAll();
        }
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
        Throwable cause = cause();
        if (cause == null) {
            return;
        }

        PlatformDependent.throwException(cause);
    }

    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        if (isDone()) {
            return true;
        }

        if (timeoutNanos <= 0) {
            return isDone();
        }

        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                    if (isDone()) {
                        return true;
                    }
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        decWaiters();
                    }
                }
                if (isDone()) {
                    return true;
                } else {
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    //前面一直使用的异常存储的的类很简单就一个异常类存储的字段，而在之前也有很多比较都是根据这个字段进行的
    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    //使用传入的执行器进行execute方法的调用
    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
