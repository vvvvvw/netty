/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.traffic;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Counts the number of read and written bytes for rate-limiting traffic.
 * <p>
 * It computes the statistics for both inbound and outbound traffic periodically at the given
 * {@code checkInterval}, and calls the {@link AbstractTrafficShapingHandler#doAccounting(TrafficCounter)} method back.
 * If the {@code checkInterval} is {@code 0}, no accounting will be done and statistics will only be computed at each
 * receive or write operation.
 * </p>
 */
/*
对读和写的字节进行计数以用于限制流量。
它会根据给定的检测间期周期性的计算统计入站和出站的流量，
并会回调AbstractTrafficShapingHandler的doAccounting方法。
如果检测间期（checkInterval）是0，将不会进行计数并且统计
只会在每次读或写操作时进行计算。
 */
/*
TrafficCounter会在每个检测间期
（checkInterval）调用AbstractTrafficShapingHandler处理器的doAccounting方法。
 */
public class TrafficCounter {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(TrafficCounter.class);

    /**
     * @return the time in ms using nanoTime, so not real EPOCH time but elapsed time in ms.
     */
    public static long milliSecondFromNano() {
        return System.nanoTime() / 1000000;
    }

    /**
     * Current written bytes
     */
    //// 从 流量定时检测任务 上一次执行到 当前 之间 写入的字节数(包括调用了write操作，但是可能由于 限流导致还没有写入到 ChannelOutboundBuffer的数据)
    private final AtomicLong currentWrittenBytes = new AtomicLong();

    /**
     * Current read bytes
     */
    //// 从 流量定时检测任务 上一次执行到 当前 之间 读取的字节数
    private final AtomicLong currentReadBytes = new AtomicLong();

    /**
     * Last writing time during current check interval
     */
    //当前检测间隔中
    private long writingTime;

    /**
     * Last reading delay during current check interval
     */
    //下一次会读取字节的时间
    //在当前检测间隔 中的最新读发生的时间（是在上一次检测时间检测到，可能当前还在读暂停中，因此
    //该时间可能大于当前时间）(ms)
    private long readingTime;

    /**
     * Long life written bytes
     */
    //整个生命周期中 写入的字节数量(包括调用了write操作，但是可能由于 限流导致还没有写入到 ChannelOutboundBuffer的数据)
    private final AtomicLong cumulativeWrittenBytes = new AtomicLong();

    /**
     * Long life read bytes
     */
    //整个生命周期中 读取的字节数量
    private final AtomicLong cumulativeReadBytes = new AtomicLong();

    /**
     * Last Time where cumulative bytes where reset to zero: this time is a real EPOC time (informative only)
     */
    //上一次 cumulativeReadBytes重置为0 的时间
    private long lastCumulativeTime;

    /**
     * Last writing bandwidth
     */
    // 从 流量定时检测任务 上上次执行到 上一次执行 之间 写入的字节数 的吞吐量(每秒), lastWriteThroughput = lastWrittenBytes * 1000 / interval（(包括调用了write操作，但是可能由于 限流导致还没有写入到 ChannelOutboundBuffer的数据)）
    private long lastWriteThroughput;

    /**
     * Last reading bandwidth
     */
    // 从 流量定时检测任务 上上次执行到 上一次执行 之间 读取的字节数 的吞吐量(每秒), lastReadThroughput= lastReadBytes * 1000 / interval
    private long lastReadThroughput;

    /**
     * Last Time Check taken
     */
    // 上次 流量监控定时任务更新指标 的时间，毫秒
    final AtomicLong lastTime = new AtomicLong();

    /**
     * Last written bytes number during last check interval
     */
    // 从 流量定时检测任务 上上次执行到 上一次执行 之间 写入的字节数(包括调用了write操作，但是可能由于 限流导致还没有写入到 ChannelOutboundBuffer的数据)
    private volatile long lastWrittenBytes;

    /**
     * Last read bytes number during last check interval
     */
    // 从 流量定时检测任务 上上次执行到 上一次执行 之间 读取的字节数
    private volatile long lastReadBytes;

    /**
     * Last future writing time during last check interval
     */
    // 和 lastReadingTime含义一致
    private volatile long lastWritingTime;

    /**
     * Last reading time during last check interval
     */
    //上一个时间间隔中 最后一次调用readTimeToWait  计算出 下一次能读取数据的时间(ms)
            //和readingTime的区别是，lastReadingTime是上一个时间间隔中 最后一次调用readTimeToWait  计算出 下一次能读取数据的时间
            //而 readingTime 是 是上一次调用readTimeToWait  计算出 下一次能读取数据的时间
    private volatile long lastReadingTime;

    /**
     * Real written bytes
     */
    //上上次执行到 上一次执行 之间真实 已经写入到 ChannelOutboundBuffer 的数据
    private final AtomicLong realWrittenBytes = new AtomicLong();

    /**
     * Real writing bandwidth
     */
    //上上次执行到 上一次执行 之间真实 写入到 ChannelOutboundBuffer 的数据 的吞吐量 /s
    private long realWriteThroughput;

    /**
     * Delay between two captures
     */
    //流量检测 定时任务 的执行时间间隔
    final AtomicLong checkInterval = new AtomicLong(
            AbstractTrafficShapingHandler.DEFAULT_CHECK_INTERVAL);

    // default 1 s

    /**
     * Name of this Monitor
     */
    final String name;

    /**
     * The associated TrafficShapingHandler
     */
    final AbstractTrafficShapingHandler trafficShapingHandler;

    /**
     * Executor that will run the monitor
     */
    final ScheduledExecutorService executor;
    /**
     * Monitor created once in start()
     */
    Runnable monitor;
    /**
     * used in stop() to cancel the timer
     */
    volatile ScheduledFuture<?> scheduledFuture;

    /**
     * Is Monitor active
     */
    //是否 流量检测 更新线程 已经开启
    volatile boolean monitorActive;

    /**
     * Class to implement monitoring at fix delay
     *
     */
    //定时任务，更新检测流量
    private final class TrafficMonitoringTask implements Runnable {
        @Override
        public void run() {
            if (!monitorActive) {
                return;
            }
            resetAccounting(milliSecondFromNano());
            if (trafficShapingHandler != null) {
                trafficShapingHandler.doAccounting(TrafficCounter.this);
            }
            scheduledFuture = executor.schedule(this, checkInterval.get(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Start the monitoring process.
     */
    //开启定时任务：更新检测流量
    public synchronized void start() {
        //如果定时任务已经开启，直接返回
        if (monitorActive) {
            return;
        }
        lastTime.set(milliSecondFromNano());
        long localCheckInterval = checkInterval.get();
        //如果 executor为空，说明 是 GlobalChannelTrafficCounter使用的 各个channel的统计器
        //这些统计器都会被GlobalChannelTrafficCounter.MixedTrafficMonitoringTask来统一定时统计
        // if executor is null, it means it is piloted by a GlobalChannelTrafficCounter, so no executor
        if (localCheckInterval > 0 && executor != null) {
            monitorActive = true;
            monitor = new TrafficMonitoringTask();
            scheduledFuture =
                executor.schedule(monitor, localCheckInterval, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stop the monitoring process.
     */
    //停止检测进程
    public synchronized void stop() {
        if (!monitorActive) {
            return;
        }
        monitorActive = false;
        resetAccounting(milliSecondFromNano());
        if (trafficShapingHandler != null) {
            trafficShapingHandler.doAccounting(this);
        }
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    /**
     * Reset the accounting on Read and Write.
     *
     * @param newLastTime the milliseconds unix timestamp that we should be considered up-to-date for.
     */
    //计算各种指标
    synchronized void resetAccounting(long newLastTime) {
        long interval = newLastTime - lastTime.getAndSet(newLastTime);
        if (interval == 0) {
            // nothing to do
            return;
        }
        if (logger.isDebugEnabled() && interval > checkInterval() << 1) {
            logger.debug("Acct schedule not ok: " + interval + " > 2*" + checkInterval() + " from " + name);
        }
        lastReadBytes = currentReadBytes.getAndSet(0);
        lastWrittenBytes = currentWrittenBytes.getAndSet(0);
        lastReadThroughput = lastReadBytes * 1000 / interval;
        // nb byte / checkInterval in ms * 1000 (1s)
        lastWriteThroughput = lastWrittenBytes * 1000 / interval;
        // nb byte / checkInterval in ms * 1000 (1s)
        realWriteThroughput = realWrittenBytes.getAndSet(0) * 1000 / interval;
        lastWritingTime = Math.max(lastWritingTime, writingTime);
        lastReadingTime = Math.max(lastReadingTime, readingTime);
    }

    /**
     * Constructor with the {@link AbstractTrafficShapingHandler} that hosts it, the {@link ScheduledExecutorService}
     * to use, its name, the checkInterval between two computations in milliseconds.
     *
     * @param executor
     *            the underlying executor service for scheduling checks, might be null when used
     * from {@link GlobalChannelTrafficCounter}.
     * @param name
     *            the name given to this monitor.
     * @param checkInterval
     *            the checkInterval in millisecond between two computations.
     */
    public TrafficCounter(ScheduledExecutorService executor, String name, long checkInterval) {
        if (name == null) {
            throw new NullPointerException("name");
        }

        trafficShapingHandler = null;
        this.executor = executor;
        this.name = name;

        init(checkInterval);
    }

    /**
     * Constructor with the {@link AbstractTrafficShapingHandler} that hosts it, the Timer to use, its
     * name, the checkInterval between two computations in millisecond.
     *
     * @param trafficShapingHandler
     *            the associated AbstractTrafficShapingHandler.
     * @param executor
     *            the underlying executor service for scheduling checks, might be null when used
     * from {@link GlobalChannelTrafficCounter}.
     * @param name
     *            the name given to this monitor.
     * @param checkInterval
     *            the checkInterval in millisecond between two computations.
     */
    public TrafficCounter(
            AbstractTrafficShapingHandler trafficShapingHandler, ScheduledExecutorService executor,
            String name, long checkInterval) {

        if (trafficShapingHandler == null) {
            throw new IllegalArgumentException("trafficShapingHandler");
        }
        if (name == null) {
            throw new NullPointerException("name");
        }

        this.trafficShapingHandler = trafficShapingHandler;
        this.executor = executor;
        this.name = name;

        init(checkInterval);
    }

    private void init(long checkInterval) {
        // absolute time: informative only
        lastCumulativeTime = System.currentTimeMillis();
        writingTime = milliSecondFromNano();
        readingTime = writingTime;
        lastWritingTime = writingTime;
        lastReadingTime = writingTime;
        configure(checkInterval);
    }

    /**
     * Change checkInterval between two computations in millisecond.
     *
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    //更新 检测时间间隔
    public void configure(long newCheckInterval) {
        long newInterval = newCheckInterval / 10 * 10;
        //如果 检测时间间隔被更新
        if (checkInterval.getAndSet(newInterval) != newInterval) {
            if (newInterval <= 0) {
                //如果 时间间隔小于等于0，则停止定时任务
                stop();
                // No more active monitoring
                lastTime.set(milliSecondFromNano());
            } else {
                // Start if necessary
                start();
            }
        }
    }

    /**
     * Computes counters for Read.
     *
     * @param recv
     *            the size in bytes to read
     */
    void bytesRecvFlowControl(long recv) {
        currentReadBytes.addAndGet(recv);
        cumulativeReadBytes.addAndGet(recv);
    }

    /**
     * Computes counters for Write.
     *
     * @param write
     *            the size in bytes to write
     */
    void bytesWriteFlowControl(long write) {
        currentWrittenBytes.addAndGet(write);
        cumulativeWrittenBytes.addAndGet(write);
    }

    /**
     * Computes counters for Real Write.
     *
     * @param write
     *            the size in bytes to write
     */
    void bytesRealWriteFlowControl(long write) {
        realWrittenBytes.addAndGet(write);
    }

    /**
     * @return the current checkInterval between two computations of traffic counter
     *         in millisecond.
     */
    public long checkInterval() {
        return checkInterval.get();
    }

    /**
     * @return the Read Throughput in bytes/s computes in the last check interval.
     */
    public long lastReadThroughput() {
        return lastReadThroughput;
    }

    /**
     * @return the Write Throughput in bytes/s computes in the last check interval.
     */
    public long lastWriteThroughput() {
        return lastWriteThroughput;
    }

    /**
     * @return the number of bytes read during the last check Interval.
     */
    public long lastReadBytes() {
        return lastReadBytes;
    }

    /**
     * @return the number of bytes written during the last check Interval.
     */
    public long lastWrittenBytes() {
        return lastWrittenBytes;
    }

    /**
     * @return the current number of bytes read since the last checkInterval.
     */
    public long currentReadBytes() {
        return currentReadBytes.get();
    }

    /**
     * @return the current number of bytes written since the last check Interval.
     */
    public long currentWrittenBytes() {
        return currentWrittenBytes.get();
    }

    /**
     * @return the Time in millisecond of the last check as of System.currentTimeMillis().
     */
    public long lastTime() {
        return lastTime.get();
    }

    /**
     * @return the cumulativeWrittenBytes
     */
    public long cumulativeWrittenBytes() {
        return cumulativeWrittenBytes.get();
    }

    /**
     * @return the cumulativeReadBytes
     */
    public long cumulativeReadBytes() {
        return cumulativeReadBytes.get();
    }

    /**
     * @return the lastCumulativeTime in millisecond as of System.currentTimeMillis()
     * when the cumulative counters were reset to 0.
     */
    public long lastCumulativeTime() {
        return lastCumulativeTime;
    }

    /**
     * @return the realWrittenBytes
     */
    public AtomicLong getRealWrittenBytes() {
        return realWrittenBytes;
    }

    /**
     * @return the realWriteThroughput
     */
    public long getRealWriteThroughput() {
        return realWriteThroughput;
    }

    /**
     * Reset both read and written cumulative bytes counters and the associated absolute time
     * from System.currentTimeMillis().
     */
    public void resetCumulativeTime() {
        lastCumulativeTime = System.currentTimeMillis();
        cumulativeReadBytes.set(0);
        cumulativeWrittenBytes.set(0);
    }

    /**
     * @return the name of this TrafficCounter.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and the max wait
     * time.
     *
     * @param size
     *            the recv size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @return the current time to wait (in ms) if needed for Read operation.
     */
    @Deprecated
    public long readTimeToWait(final long size, final long limitTraffic, final long maxTime) {
        return readTimeToWait(size, limitTraffic, maxTime, milliSecondFromNano());
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and the max wait
     * time.
     *
     * @param size
     *            the recv size
     * @param limitTraffic
     *            the traffic limit in bytes per second 每秒
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @param now the current time
     * @return the current time to wait (in ms) if needed for Read operation.
     */
    // 根据 限制的流量速率和 最大等待时间 返回 msg需要 等待多少时间才能读取
    public long readTimeToWait(final long size, final long limitTraffic, final long maxTime, final long now) {
        bytesRecvFlowControl(size);
        if (size == 0 || limitTraffic == 0) {
            return 0;
        }
        //上次检查时间
        final long lastTimeCheck = lastTime.get();
        //上次暂停到当前读取字节数(包含了本次的size)
        long sum = currentReadBytes.get();
        //在当前检测间隔 中的最新读发生的时间（是在上一次检测时间检测到，可能当前还在读暂停中，因此
        //该时间可能大于当前时间）
        long localReadingTime = readingTime;
        //上次检测时的读取字节数
        long lastRB = lastReadBytes;
        //上次检查到当前的时间间隔
        final long interval = now - lastTimeCheck;
        //下一次 能读取数据的 时间到 上一次 流量监控定时任务 检测时间 的时间间隔
        long pastDelay = Math.max(lastReadingTime - lastTimeCheck, 0);
        //上一次检查的时间 到当前的时间间隔 大于AbstractTrafficShapingHandler.MINIMAL_WAIT
        if (interval > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            // Enough interval time to compute shaping
            //按照限定流量速度 读取 sum 数量的字节 需要的时间：sum * 1000 / limitTraffic
            //inteval:上次检查到当前的时间间隔
            //pastDelay:下一次 能读取数据的 时间到 上一次 流量监控定时任务 检测时间 的时间间隔
            //time: 还需要暂停的时间(只计算 已经读取的currentReadBytes的字节)
            //limitTraffic单位:B/s
            long time = sum * 1000 / limitTraffic - interval + pastDelay;
            if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Time: " + time + ':' + sum + ':'
                            + interval + ':' + pastDelay);
                }
                //如果 还需要暂停的时间>最大延迟时间,并且 最新计算出来的读时间-上次计算出来的读取时间>最大延迟时间
                if (time > maxTime && now + time - localReadingTime > maxTime) {
                    //设置下一次 可写入的时间就为 当前时间+ 最大延迟时间
                    time = maxTime;
                }
                //下次读取时间
                readingTime = Math.max(localReadingTime, now + time);
                //延迟时间
                return time;
            }
            readingTime = Math.max(localReadingTime, now);
            return 0;
        }

        //如果上一次检查的时间 到当前时间 小于AbstractTrafficShapingHandler.MINIMAL_WAIT(时间太短)
        //加上上一次 检测的字节数和 时间间隔
        // take the last read interval check to get enough interval time
        long lastsum = sum + lastRB;
        long lastinterval = interval + checkInterval.get();
        long time = lastsum * 1000 / limitTraffic - lastinterval + pastDelay;
        if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            if (logger.isDebugEnabled()) {
                logger.debug("Time: " + time + ':' + lastsum + ':' + lastinterval + ':' + pastDelay);
            }
            if (time > maxTime && now + time - localReadingTime > maxTime) {
                time = maxTime;
            }
            readingTime = Math.max(localReadingTime, now + time);
            return time;
        }
        readingTime = Math.max(localReadingTime, now);
        return 0;
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and
     * the max wait time.
     *
     * @param size
     *            the write size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @return the current time to wait (in ms) if needed for Write operation.
     */
    @Deprecated
    public long writeTimeToWait(final long size, final long limitTraffic, final long maxTime) {
        return writeTimeToWait(size, limitTraffic, maxTime, milliSecondFromNano());
    }

    /**
     * Returns the time to wait (if any) for the given length message, using the given limitTraffic and
     * the max wait time.
     *
     * @param size
     *            the write size
     * @param limitTraffic
     *            the traffic limit in bytes per second.
     * @param maxTime
     *            the max time in ms to wait in case of excess of traffic.
     * @param now the current time
     * @return the current time to wait (in ms) if needed for Write operation.
     */
    // 根据 限制的流量速率和 最大等待时间 返回 msg需要 等待多少时间才能发送
    public long writeTimeToWait(final long size, final long limitTraffic, final long maxTime, final long now) {
        bytesWriteFlowControl(size);
        if (size == 0 || limitTraffic == 0) {
            return 0;
        }
        final long lastTimeCheck = lastTime.get();
        long sum = currentWrittenBytes.get();
        long lastWB = lastWrittenBytes;
        long localWritingTime = writingTime;
        long pastDelay = Math.max(lastWritingTime - lastTimeCheck, 0);
        final long interval = now - lastTimeCheck;
        if (interval > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            // Enough interval time to compute shaping
            long time = sum * 1000 / limitTraffic - interval + pastDelay;
            if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Time: " + time + ':' + sum + ':' + interval + ':' + pastDelay);
                }
                if (time > maxTime && now + time - localWritingTime > maxTime) {
                    time = maxTime;
                }
                writingTime = Math.max(localWritingTime, now + time);
                return time;
            }
            writingTime = Math.max(localWritingTime, now);
            return 0;
        }
        // take the last write interval check to get enough interval time
        long lastsum = sum + lastWB;
        long lastinterval = interval + checkInterval.get();
        long time = lastsum * 1000 / limitTraffic - lastinterval + pastDelay;
        if (time > AbstractTrafficShapingHandler.MINIMAL_WAIT) {
            if (logger.isDebugEnabled()) {
                logger.debug("Time: " + time + ':' + lastsum + ':' + lastinterval + ':' + pastDelay);
            }
            if (time > maxTime && now + time - localWritingTime > maxTime) {
                time = maxTime;
            }
            writingTime = Math.max(localWritingTime, now + time);
            return time;
        }
        writingTime = Math.max(localWritingTime, now);
        return 0;
    }

    @Override
    public String toString() {
        return new StringBuilder(165).append("Monitor ").append(name)
                .append(" Current Speed Read: ").append(lastReadThroughput >> 10).append(" KB/s, ")
                .append("Asked Write: ").append(lastWriteThroughput >> 10).append(" KB/s, ")
                .append("Real Write: ").append(realWriteThroughput >> 10).append(" KB/s, ")
                .append("Current Read: ").append(currentReadBytes.get() >> 10).append(" KB, ")
                .append("Current asked Write: ").append(currentWrittenBytes.get() >> 10).append(" KB, ")
                .append("Current real Write: ").append(realWrittenBytes.get() >> 10).append(" KB").toString();
    }
}
