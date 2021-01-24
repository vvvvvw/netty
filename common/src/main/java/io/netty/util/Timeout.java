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

/**
 * A handle associated with a {@link TimerTask} that is returned by a
 * {@link Timer}.
 */
public interface Timeout {

    /**
     * 返回创建本句柄的定时器
     * Returns the {@link Timer} that created this handle.
     */
    Timer timer();

    /**
     * 和本句柄相关联的任务
     * Returns the {@link TimerTask} which is associated with this handle.
     */
    TimerTask task();

    /**
     * 和本句柄相关联的任务 是否已经执行过
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been expired.
     */
    boolean isExpired();

    /**
     * 和本句柄相关联的任务 是否已经被取消
     * Returns {@code true} if and only if the {@link TimerTask} associated
     * with this handle has been cancelled.
     */
    boolean isCancelled();

    /**
     * Attempts to cancel the {@link TimerTask} associated with this handle.
     * If the task has been executed or cancelled already, it will return with
     * no side effect.
     * 取消 和本句柄相关的task，如果task已经执行或者已经被取消，直接返回false
     * @return True if the cancellation completed successfully, otherwise false
     */
    boolean cancel();
}
