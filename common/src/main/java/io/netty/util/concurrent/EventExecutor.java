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
package io.netty.util.concurrent;

/**
 * The {@link EventExecutor} is a special {@link EventExecutorGroup} which comes
 * with some handy methods to see if a {@link Thread} is executed in a event loop.
 * Besides this, it also extends the {@link EventExecutorGroup} to allow for a generic
 * way to access methods.
 *
 */
//EventExecutor在EventExecutorGroup基础上提供了便捷的方法，比如查看判断当前调用本方法所在线程是否是本EventLoop线程
public interface EventExecutor extends EventExecutorGroup {

    /**
     * Returns a reference to itself.
     */
    //返回本实例
    @Override
    EventExecutor next();

    /**
     * Return the {@link EventExecutorGroup} which is the parent of this {@link EventExecutor},
     */
    //返回 本实例对应的 EventExecutorGroup
    EventExecutorGroup parent();

    /**
     * Calls {@link #inEventLoop(Thread)} with {@link Thread#currentThread()} as argument
     */
    // 判断当前调用本方法所在线程是否是本EventLoop线程，如果是同一个线程，则不存在多线程
    //并发操作问题
    boolean inEventLoop();

    /**
     * Return {@code true} if the given {@link Thread} is executed in the event loop,
     * {@code false} otherwise.
     */
    //是否 thread 对应的是 本EventLoop线程
    boolean inEventLoop(Thread thread);

    /**
     * Return a new {@link Promise}.
     */
    //创建一个使用本 EventLoop 作为notifyListener执行器 的Promise
    <V> Promise<V> newPromise();

    /**
     * Create a new {@link ProgressivePromise}.
     */
    //有进度条的 promise
    <V> ProgressivePromise<V> newProgressivePromise();

    /**
     * Create a new {@link Future} which is marked as succeeded already. So {@link Future#isSuccess()}
     * will return {@code true}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    //创建一个 成功调用的 future，future中所有的listener都会立即执行，notifyListener使用本EventExecutor
    <V> Future<V> newSucceededFuture(V result);

    /**
     * Create a new {@link Future} which is marked as failed already. So {@link Future#isSuccess()}
     * will return {@code false}. All {@link FutureListener} added to it will be notified directly. Also
     * every call of blocking methods will just return without blocking.
     */
    //创建一个 失败调用的 future，future中所有的listener都会立即执行，notifyListener使用本EventExecutor
    <V> Future<V> newFailedFuture(Throwable cause);
}
