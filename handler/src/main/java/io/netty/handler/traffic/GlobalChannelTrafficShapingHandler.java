/*
 * Copyright 2014 The Netty Project
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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.AbstractCollection;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This implementation of the {@link AbstractTrafficShapingHandler} is for global
 * and per channel traffic shaping, that is to say a global limitation of the bandwidth, whatever
 * the number of opened channels and a per channel limitation of the bandwidth.<br><br>
 * This version shall not be in the same pipeline than other TrafficShapingHandler.<br><br>
 *
 * The general use should be as follow:<br>
 * <ul>
 * <li>Create your unique GlobalChannelTrafficShapingHandler like:<br><br>
 * <tt>GlobalChannelTrafficShapingHandler myHandler = new GlobalChannelTrafficShapingHandler(executor);</tt><br><br>
 * The executor could be the underlying IO worker pool<br>
 * <tt>pipeline.addLast(myHandler);</tt><br><br>
 *
 * <b>Note that this handler has a Pipeline Coverage of "all" which means only one such handler must be created
 * and shared among all channels as the counter must be shared among all channels.</b><br><br>
 *
 * Other arguments can be passed like write or read limitation (in bytes/s where 0 means no limitation)
 * or the check interval (in millisecond) that represents the delay between two computations of the
 * bandwidth and so the call back of the doAccounting method (0 means no accounting at all).<br>
 * Note that as this is a fusion of both Global and Channel Traffic Shaping, limits are in 2 sets,
 * respectively Global and Channel.<br><br>
 *
 * A value of 0 means no accounting for checkInterval. If you need traffic shaping but no such accounting,
 * it is recommended to set a positive value, even if it is high since the precision of the
 * Traffic Shaping depends on the period where the traffic is computed. The highest the interval,
 * the less precise the traffic shaping will be. It is suggested as higher value something close
 * to 5 or 10 minutes.<br><br>
 *
 * maxTimeToWait, by default set to 15s, allows to specify an upper bound of time shaping.<br><br>
 * </li>
 * <li>In your handler, you should consider to use the {@code channel.isWritable()} and
 * {@code channelWritabilityChanged(ctx)} to handle writability, or through
 * {@code future.addListener(new GenericFutureListener())} on the future returned by
 * {@code ctx.write()}.</li>
 * <li>You shall also consider to have object size in read or write operations relatively adapted to
 * the bandwidth you required: for instance having 10 MB objects for 10KB/s will lead to burst effect,
 * while having 100 KB objects for 1 MB/s should be smoothly handle by this TrafficShaping handler.<br><br></li>
 * <li>Some configuration methods will be taken as best effort, meaning
 * that all already scheduled traffics will not be
 * changed, but only applied to new traffics.<br>
 * So the expected usage of those methods are to be used not too often,
 * accordingly to the traffic shaping configuration.</li>
 * </ul><br>
 *
 * Be sure to call {@link #release()} once this handler is not needed anymore to release all internal resources.
 * This will not shutdown the {@link EventExecutor} as it may be shared, so you need to do this by your own.
 */
//相比于GlobalTrafficShapingHandler增加了一个误差概念，以平衡各个Channel间的读/写操作。
//也就是说，使得各个Channel间的读/写操作尽量均衡。比如，尽量避免不同Channel的大数据包都延迟近乎一样的时间再操作，
//以及如果小数据包在一个大数据包后才发送，则减少该小数据包的延迟发送时间等。。

@Sharable
public class GlobalChannelTrafficShapingHandler extends AbstractTrafficShapingHandler {
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(GlobalChannelTrafficShapingHandler.class);
    /**
     * All queues per channel
     */
    // Map<channelhash,PerChannel>
    final ConcurrentMap<Integer, PerChannel> channelQueues = PlatformDependent.newConcurrentHashMap();

    /**
     * Global queues size
     */
    //所有channel的 待写队列中 的数据量总和
    private final AtomicLong queuesSize = new AtomicLong();

    /**
     * Maximum cumulative writing bytes for one channel among all (as long as channels stay the same)
     */
    // 在所有channel中 累计写入最多的channel的累计写入字节数
    private final AtomicLong cumulativeWrittenBytes = new AtomicLong();

    /**
     * Maximum cumulative read bytes for one channel among all (as long as channels stay the same)
     */
    // 在所有channel中 累计读取最多的channel的累计读取字节数
    private final AtomicLong cumulativeReadBytes = new AtomicLong();

    /**
     * Max size in the list before proposing to stop writing new objects from next handlers
     * for all channel (global)
     */
    // 所有channel的 待写队列中 的数据量总和 如果大于400，则 将 可写状态 设置为 不可写
    volatile long maxGlobalWriteSize = DEFAULT_MAX_SIZE * 100; // default 400MB

    /**
     * Limit in B/s to apply to write
     */
    private volatile long writeChannelLimit;

    /**
     * Limit in B/s to apply to read
     */
    //单个channel 流量控制
    private volatile long readChannelLimit;

    private static final float DEFAULT_DEVIATION = 0.1F;
    private static final float MAX_DEVIATION = 0.4F;
    private static final float DEFAULT_SLOWDOWN = 0.4F;
    private static final float DEFAULT_ACCELERATION = -0.1F;
    private volatile float maxDeviation;
    //对于发送或者读取字节数 较少 的客户端，将因子设置为-x%,（最大值为0，表示无加速因子）,默认值为-10％（-0.1）。
    private volatile float accelerationFactor;
    private volatile float slowDownFactor;
    private volatile boolean readDeviationActive;
    private volatile boolean writeDeviationActive;

    static final class PerChannel {
        ArrayDeque<ToSend> messagesQueue;
        TrafficCounter channelTrafficCounter;
        long queueSize;
        long lastWriteTimestamp;
        long lastReadTimestamp;
    }

    /**
     * Create the global TrafficCounter
     */
    void createGlobalTrafficCounter(ScheduledExecutorService executor) {
        // Default
        setMaxDeviation(DEFAULT_DEVIATION, DEFAULT_SLOWDOWN, DEFAULT_ACCELERATION);
        if (executor == null) {
            throw new IllegalArgumentException("Executor must not be null");
        }
        TrafficCounter tc = new GlobalChannelTrafficCounter(this, executor, "GlobalChannelTC", checkInterval);
        setTrafficCounter(tc);
        tc.start();
    }

    @Override
    protected int userDefinedWritabilityIndex() {
        return AbstractTrafficShapingHandler.GLOBALCHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX;
    }

    /**
     * Create a new instance.
     *
     * @param executor
     *            the {@link ScheduledExecutorService} to use for the {@link TrafficCounter}.
     * @param writeGlobalLimit
     *            0 or a limit in bytes/s
     * @param readGlobalLimit
     *            0 or a limit in bytes/s
     * @param writeChannelLimit
     *            0 or a limit in bytes/s
     * @param readChannelLimit
     *            0 or a limit in bytes/s
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     * @param maxTime
     *            The maximum delay to wait in case of traffic excess.
     */
    public GlobalChannelTrafficShapingHandler(ScheduledExecutorService executor,
            long writeGlobalLimit, long readGlobalLimit,
            long writeChannelLimit, long readChannelLimit,
            long checkInterval, long maxTime) {
        super(writeGlobalLimit, readGlobalLimit, checkInterval, maxTime);
        createGlobalTrafficCounter(executor);
        this.writeChannelLimit = writeChannelLimit;
        this.readChannelLimit = readChannelLimit;
    }

    /**
     * Create a new instance.
     *
     * @param executor
     *          the {@link ScheduledExecutorService} to use for the {@link TrafficCounter}.
     * @param writeGlobalLimit
     *            0 or a limit in bytes/s
     * @param readGlobalLimit
     *            0 or a limit in bytes/s
     * @param writeChannelLimit
     *            0 or a limit in bytes/s
     * @param readChannelLimit
     *            0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    public GlobalChannelTrafficShapingHandler(ScheduledExecutorService executor,
            long writeGlobalLimit, long readGlobalLimit,
            long writeChannelLimit, long readChannelLimit,
            long checkInterval) {
        super(writeGlobalLimit, readGlobalLimit, checkInterval);
        this.writeChannelLimit = writeChannelLimit;
        this.readChannelLimit = readChannelLimit;
        createGlobalTrafficCounter(executor);
    }

    /**
     * Create a new instance.
     *
     * @param executor
     *          the {@link ScheduledExecutorService} to use for the {@link TrafficCounter}.
     * @param writeGlobalLimit
     *            0 or a limit in bytes/s
     * @param readGlobalLimit
     *            0 or a limit in bytes/s
     * @param writeChannelLimit
     *            0 or a limit in bytes/s
     * @param readChannelLimit
     *            0 or a limit in bytes/s
     */
    public GlobalChannelTrafficShapingHandler(ScheduledExecutorService executor,
            long writeGlobalLimit, long readGlobalLimit,
            long writeChannelLimit, long readChannelLimit) {
        super(writeGlobalLimit, readGlobalLimit);
        this.writeChannelLimit = writeChannelLimit;
        this.readChannelLimit = readChannelLimit;
        createGlobalTrafficCounter(executor);
    }

    /**
     * Create a new instance.
     *
     * @param executor
     *          the {@link ScheduledExecutorService} to use for the {@link TrafficCounter}.
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    public GlobalChannelTrafficShapingHandler(ScheduledExecutorService executor, long checkInterval) {
        super(checkInterval);
        createGlobalTrafficCounter(executor);
    }

    /**
     * Create a new instance.
     *
     * @param executor
     *          the {@link ScheduledExecutorService} to use for the {@link TrafficCounter}.
     */
    public GlobalChannelTrafficShapingHandler(ScheduledExecutorService executor) {
        createGlobalTrafficCounter(executor);
    }

    /**
     * @return the current max deviation
     */
    public float maxDeviation() {
        return maxDeviation;
    }

    /**
     * @return the current acceleration factor
     */
    public float accelerationFactor() {
        return accelerationFactor;
    }

    /**
     * @return the current slow down factor
     */
    public float slowDownFactor() {
        return slowDownFactor;
    }

    /**
     * @param maxDeviation
     *            the maximum deviation to allow during computation of average, default deviation
     *            being 0.1, so +/-10% of the desired bandwidth. Maximum being 0.4.
     *  计算平均值时允许的最大偏差，默认偏差为0.1，即所需带宽的+/- 10％。 最大值为0.4。
     * @param slowDownFactor
     *            the factor set as +x% to the too fast client (minimal value being 0, meaning no
     *            slow down factor), default being 40% (0.4).
     * 对于速度过快的客户端（最小值为0，表示没有减速因子），将因子设置为+ x％，默认值为40％（0.4）。
     * @param accelerationFactor
     *            the factor set as -x% to the too slow client (maximal value being 0, meaning no
     *            acceleration factor), default being -10% (-0.1).
     * 对于速度过慢的客户端，将因子设置为-x%,（最大值为0，表示无加速因子）,默认值为-10％（-0.1）。
     */
    public void setMaxDeviation(float maxDeviation, float slowDownFactor, float accelerationFactor) {
        if (maxDeviation > MAX_DEVIATION) {
            throw new IllegalArgumentException("maxDeviation must be <= " + MAX_DEVIATION);
        }
        if (slowDownFactor < 0) {
            throw new IllegalArgumentException("slowDownFactor must be >= 0");
        }
        if (accelerationFactor > 0) {
            throw new IllegalArgumentException("accelerationFactor must be <= 0");
        }
        this.maxDeviation = maxDeviation;
        this.accelerationFactor = 1 + accelerationFactor;
        this.slowDownFactor = 1 + slowDownFactor;
    }

    private void computeDeviationCumulativeBytes() {
        // compute the maximum cumulativeXxxxBytes among still connected Channels
        long maxWrittenBytes = 0;
        long maxReadBytes = 0;
        long minWrittenBytes = Long.MAX_VALUE;
        long minReadBytes = Long.MAX_VALUE;
        for (PerChannel perChannel : channelQueues.values()) {
            long value = perChannel.channelTrafficCounter.cumulativeWrittenBytes();
            if (maxWrittenBytes < value) {
                maxWrittenBytes = value;
            }
            if (minWrittenBytes > value) {
                minWrittenBytes = value;
            }
            value = perChannel.channelTrafficCounter.cumulativeReadBytes();
            if (maxReadBytes < value) {
                maxReadBytes = value;
            }
            if (minReadBytes > value) {
                minReadBytes = value;
            }
        }
        boolean multiple = channelQueues.size() > 1;
        //如果 读取字节数 最小的 channel读取的字节数 小于 读取字节数最多 的channel 读取的字节数的一半，开启读偏移
        readDeviationActive = multiple && minReadBytes < maxReadBytes / 2;
        //如果 写入字节数 最小的 channel读取的字节数 小于 写入字节数最多 的channel 写入的字节数的一半，开启写偏移
        writeDeviationActive = multiple && minWrittenBytes < maxWrittenBytes / 2;
        // 将 写入字节数最多 的channel 写入的字节数 作为  限流器 累计写入的字节数
        cumulativeWrittenBytes.set(maxWrittenBytes);
        // 将 读取字节数最多 的channel 读取的字节数 作为  限流器 累计读取的字节数
        cumulativeReadBytes.set(maxReadBytes);
    }

    @Override
    protected void doAccounting(TrafficCounter counter) {
        computeDeviationCumulativeBytes();
        super.doAccounting(counter);
    }

    // ratio 处于(maxDeviation,1], 等待时间为 wait* slowDownFactor
    // ratio 处于 [1,maxDeviation,maxDeviation) 等待时间为wait
    // ratio 处于 （0,maxDeviation], 等待时间为 wait*accelerationFactor
    // ratio 为 0，等待时间为 wait
    private long computeBalancedWait(float maxLocal, float maxGlobal, long wait) {
        if (maxGlobal == 0) {
            //如果 没有读取过字节的话，则直接返回
            // no change
            return wait;
        }
        float ratio = maxLocal / maxGlobal;
        // if in the boundaries, same value
        //如果 该channel的流量 已经占据的 全局流量的比例 大于 阈值，
        if (ratio > maxDeviation) {
            if (ratio < 1 - maxDeviation) {
                return wait;
            } else {
                ratio = slowDownFactor;
                if (wait < MINIMAL_WAIT) {
                    wait = MINIMAL_WAIT;
                }
            }
        } else {
            ratio = accelerationFactor;
        }
        return (long) (wait * ratio);
    }

    /**
     * @return the maxGlobalWriteSize
     */
    public long getMaxGlobalWriteSize() {
        return maxGlobalWriteSize;
    }

    /**
     * Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.<br>
     * So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.
     *
     * @param maxGlobalWriteSize the maximum Global Write Size allowed in the buffer
     *            globally for all channels before write suspended is set.
     */
    public void setMaxGlobalWriteSize(long maxGlobalWriteSize) {
        if (maxGlobalWriteSize <= 0) {
            throw new IllegalArgumentException("maxGlobalWriteSize must be positive");
        }
        this.maxGlobalWriteSize = maxGlobalWriteSize;
    }

    /**
     * @return the global size of the buffers for all queues.
     */
    public long queuesSize() {
        return queuesSize.get();
    }

    /**
     * @param newWriteLimit Channel write limit
     * @param newReadLimit Channel read limit
     */
    public void configureChannel(long newWriteLimit, long newReadLimit) {
        writeChannelLimit = newWriteLimit;
        readChannelLimit = newReadLimit;
        long now = TrafficCounter.milliSecondFromNano();
        for (PerChannel perChannel : channelQueues.values()) {
            perChannel.channelTrafficCounter.resetAccounting(now);
        }
    }

    /**
     * @return Channel write limit
     */
    public long getWriteChannelLimit() {
        return writeChannelLimit;
    }

    /**
     * @param writeLimit Channel write limit
     */
    public void setWriteChannelLimit(long writeLimit) {
        writeChannelLimit = writeLimit;
        long now = TrafficCounter.milliSecondFromNano();
        for (PerChannel perChannel : channelQueues.values()) {
            perChannel.channelTrafficCounter.resetAccounting(now);
        }
    }

    /**
     * @return Channel read limit
     */
    public long getReadChannelLimit() {
        return readChannelLimit;
    }

    /**
     * @param readLimit Channel read limit
     */
    public void setReadChannelLimit(long readLimit) {
        readChannelLimit = readLimit;
        long now = TrafficCounter.milliSecondFromNano();
        for (PerChannel perChannel : channelQueues.values()) {
            perChannel.channelTrafficCounter.resetAccounting(now);
        }
    }

    /**
     * Release all internal resources of this instance.
     */
    public final void release() {
        trafficCounter.stop();
    }

    private PerChannel getOrSetPerChannel(ChannelHandlerContext ctx) {
        // ensure creation is limited to one thread per channel
        Channel channel = ctx.channel();
        Integer key = channel.hashCode();
        PerChannel perChannel = channelQueues.get(key);
        if (perChannel == null) {
            perChannel = new PerChannel();
            perChannel.messagesQueue = new ArrayDeque<ToSend>();
            // Don't start it since managed through the Global one
            //给每个channel 设置一个 流量监听器，每次时间间隔结束统计完 本channel的流量，会回调 本 GlobalChannelTrafficShapingHandler的doAccounting方法
            perChannel.channelTrafficCounter = new TrafficCounter(this, null, "ChannelTC" +
                    ctx.channel().hashCode(), checkInterval);
            perChannel.queueSize = 0L;
            perChannel.lastReadTimestamp = TrafficCounter.milliSecondFromNano();
            perChannel.lastWriteTimestamp = perChannel.lastReadTimestamp;
            channelQueues.put(key, perChannel);
        }
        return perChannel;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        getOrSetPerChannel(ctx);
        trafficCounter.resetCumulativeTime();
        super.handlerAdded(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        trafficCounter.resetCumulativeTime();
        Channel channel = ctx.channel();
        Integer key = channel.hashCode();
        PerChannel perChannel = channelQueues.remove(key);
        if (perChannel != null) {
            // write operations need synchronization
            synchronized (perChannel) {
                if (channel.isActive()) {
                    for (ToSend toSend : perChannel.messagesQueue) {
                        long size = calculateSize(toSend.toSend);
                        trafficCounter.bytesRealWriteFlowControl(size);
                        perChannel.channelTrafficCounter.bytesRealWriteFlowControl(size);
                        perChannel.queueSize -= size;
                        queuesSize.addAndGet(-size);
                        ctx.write(toSend.toSend, toSend.promise);
                    }
                } else {
                    queuesSize.addAndGet(-perChannel.queueSize);
                    for (ToSend toSend : perChannel.messagesQueue) {
                        if (toSend.toSend instanceof ByteBuf) {
                            ((ByteBuf) toSend.toSend).release();
                        }
                    }
                }
                perChannel.messagesQueue.clear();
            }
        }
        releaseWriteSuspended(ctx);
        releaseReadSuspended(ctx);
        super.handlerRemoved(ctx);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        //计算当前消息大小
        long size = calculateSize(msg);
        //当前毫秒数
        long now = TrafficCounter.milliSecondFromNano();
        if (size > 0) {
            // compute the number of ms to wait before reopening the channel
            // 全局流量控制 计算出来的等待时间
            long waitGlobal = trafficCounter.readTimeToWait(size, getReadLimit(), maxTime, now);
            Integer key = ctx.channel().hashCode();
            PerChannel perChannel = channelQueues.get(key);
            long wait = 0;
            if (perChannel != null) {
                //单个channel 流量控制 计算出来的 等待时间
                wait = perChannel.channelTrafficCounter.readTimeToWait(size, readChannelLimit, maxTime, now);
                if (readDeviationActive) {
                    //如果开启了 读偏移
                    // now try to balance between the channels
                    long maxLocalRead;
                    //该 channel 总共读取的字节数量
                    maxLocalRead = perChannel.channelTrafficCounter.cumulativeReadBytes();
                    // 全局所有channel 总共读取的字节数量
                    long maxGlobalRead = cumulativeReadBytes.get();
                    if (maxLocalRead <= 0) {
                        maxLocalRead = 0;
                    }
                    if (maxGlobalRead < maxLocalRead) {
                        maxGlobalRead = maxLocalRead;
                    }
                    // 正则化本地 等待时间
                    wait = computeBalancedWait(maxLocalRead, maxGlobalRead, wait);
                }
            }
            // 取wait和waitGlobal中大的那个
            if (wait < waitGlobal) {
                wait = waitGlobal;
            }
            wait = checkWaitReadTime(ctx, wait, now);
            //如果等待时间 超过 10ms，则 关闭 该channel的读取，并且在 等待时间过后开启 该channel的读取
            if (wait >= MINIMAL_WAIT) { // At least 10ms seems a minimal
                // time in order to try to limit the traffic
                // Only AutoRead AND HandlerActive True means Context Active
                Channel channel = ctx.channel();
                ChannelConfig config = channel.config();
                if (logger.isDebugEnabled()) {
                    logger.debug("Read Suspend: " + wait + ':' + config.isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                if (config.isAutoRead() && isHandlerActive(ctx)) {
                    config.setAutoRead(false);
                    channel.attr(READ_SUSPENDED).set(true);
                    // Create a Runnable to reactive the read if needed. If one was create before it will just be
                    // reused to limit object creation
                    Attribute<Runnable> attr = channel.attr(REOPEN_TASK);
                    Runnable reopenTask = attr.get();
                    if (reopenTask == null) {
                        reopenTask = new ReopenReadTimerTask(ctx);
                        attr.set(reopenTask);
                    }
                    ctx.executor().schedule(reopenTask, wait, TimeUnit.MILLISECONDS);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Suspend final status => " + config.isAutoRead() + ':'
                                + isHandlerActive(ctx) + " will reopened at: " + wait);
                    }
                }
            }
        }
        informReadOperation(ctx, now);
        ctx.fireChannelRead(msg);
    }

    // 等待时间不超过 maxTime
    @Override
    protected long checkWaitReadTime(final ChannelHandlerContext ctx, long wait, final long now) {
        Integer key = ctx.channel().hashCode();
        PerChannel perChannel = channelQueues.get(key);
        if (perChannel != null) {
            if (wait > maxTime && now + wait - perChannel.lastReadTimestamp > maxTime) {
                wait = maxTime;
            }
        }
        return wait;
    }

    @Override
    protected void informReadOperation(final ChannelHandlerContext ctx, final long now) {
        Integer key = ctx.channel().hashCode();
        PerChannel perChannel = channelQueues.get(key);
        if (perChannel != null) {
            perChannel.lastReadTimestamp = now;
        }
    }

    private static final class ToSend {
        final long relativeTimeAction;
        final Object toSend;
        final ChannelPromise promise;
        final long size;

        private ToSend(final long delay, final Object toSend, final long size, final ChannelPromise promise) {
            relativeTimeAction = delay;
            this.toSend = toSend;
            this.size = size;
            this.promise = promise;
        }
    }

    protected long maximumCumulativeWrittenBytes() {
        return cumulativeWrittenBytes.get();
    }

    protected long maximumCumulativeReadBytes() {
        return cumulativeReadBytes.get();
    }

    /**
     * To allow for instance doAccounting to use the TrafficCounter per channel.
     * @return the list of TrafficCounters that exists at the time of the call.
     */
    public Collection<TrafficCounter> channelTrafficCounters() {
        return new AbstractCollection<TrafficCounter>() {
            @Override
            public Iterator<TrafficCounter> iterator() {
                return new Iterator<TrafficCounter>() {
                    final Iterator<PerChannel> iter = channelQueues.values().iterator();
                    @Override
                    public boolean hasNext() {
                        return iter.hasNext();
                    }
                    @Override
                    public TrafficCounter next() {
                        return iter.next().channelTrafficCounter;
                    }
                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
            @Override
            public int size() {
                return channelQueues.size();
            }
        };
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        long size = calculateSize(msg);
        long now = TrafficCounter.milliSecondFromNano();
        if (size > 0) {
            // compute the number of ms to wait before continue with the channel
            // 计算等待时间
            long waitGlobal = trafficCounter.writeTimeToWait(size, getWriteLimit(), maxTime, now);
            Integer key = ctx.channel().hashCode();
            PerChannel perChannel = channelQueues.get(key);
            long wait = 0;
            if (perChannel != null) {
                wait = perChannel.channelTrafficCounter.writeTimeToWait(size, writeChannelLimit, maxTime, now);
                if (writeDeviationActive) {
                    //如果开启了 写偏移
                    // now try to balance between the channels
                    long maxLocalWrite;
                    maxLocalWrite = perChannel.channelTrafficCounter.cumulativeWrittenBytes();
                    long maxGlobalWrite = cumulativeWrittenBytes.get();
                    if (maxLocalWrite <= 0) {
                        maxLocalWrite = 0;
                    }
                    if (maxGlobalWrite < maxLocalWrite) {
                        maxGlobalWrite = maxLocalWrite;
                    }
                    wait = computeBalancedWait(maxLocalWrite, maxGlobalWrite, wait);
                }
            }
            if (wait < waitGlobal) {
                wait = waitGlobal;
            }
            //如果 等待时间超过了 10ms
            if (wait >= MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Write suspend: " + wait + ':' + ctx.channel().config().isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                submitWrite(ctx, msg, size, wait, now, promise);
                return;
            }
        }
        // to maintain order of write
        submitWrite(ctx, msg, size, 0, now, promise);
    }

    @Override
    protected void submitWrite(final ChannelHandlerContext ctx, final Object msg,
            final long size, final long writedelay, final long now,
            final ChannelPromise promise) {
        Channel channel = ctx.channel();
        Integer key = channel.hashCode();
        PerChannel perChannel = channelQueues.get(key);
        if (perChannel == null) {
            // in case write occurs before handlerAdded is raised for this handler
            // imply a synchronized only if needed
            perChannel = getOrSetPerChannel(ctx);
        }
        final ToSend newToSend;
        long delay = writedelay;
        boolean globalSizeExceeded = false;
        // write operations need synchronization
        synchronized (perChannel) {
            //如果 该channel的待写队列为空，则直接发送
            if (writedelay == 0 && perChannel.messagesQueue.isEmpty()) {
                trafficCounter.bytesRealWriteFlowControl(size);
                perChannel.channelTrafficCounter.bytesRealWriteFlowControl(size);
                ctx.write(msg, promise);
                perChannel.lastWriteTimestamp = now;
                return;
            }
            if (delay > maxTime && now + delay - perChannel.lastWriteTimestamp > maxTime) {
                delay = maxTime;
            }
            newToSend = new ToSend(delay + now, msg, size, promise);
            perChannel.messagesQueue.addLast(newToSend);
            perChannel.queueSize += size;
            queuesSize.addAndGet(size);
            //检查单个Channel待发送的数据包是否超过了maxWriteSize（默认4M），或者延迟时间是否超过了maxWriteDelay（默认4s）。
            //如果是则关闭 该channel的可写状态
            checkWriteSuspend(ctx, delay, perChannel.queueSize);
            if (queuesSize.get() > maxGlobalWriteSize) {
                globalSizeExceeded = true;
            }
        }
        //如果所有待发送的数据大小（这里指所有Channel累积的待发送的数据大小）大于了maxGlobalWriteSize（默认400M），
        // 则标识globalSizeExceeded为true，并且调用『setUserDefinedWritability(ctx, false)』
        if (globalSizeExceeded) {
            setUserDefinedWritability(ctx, false);
        }
        //延时 发送
        final long futureNow = newToSend.relativeTimeAction;
        final PerChannel forSchedule = perChannel;
        ctx.executor().schedule(new Runnable() {
            @Override
            public void run() {
                sendAllValid(ctx, forSchedule, futureNow);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void sendAllValid(final ChannelHandlerContext ctx, final PerChannel perChannel, final long now) {
        // write operations need synchronization
        synchronized (perChannel) {
            ToSend newToSend = perChannel.messagesQueue.pollFirst();
            for (; newToSend != null; newToSend = perChannel.messagesQueue.pollFirst()) {
                if (newToSend.relativeTimeAction <= now) {
                    long size = newToSend.size;
                    trafficCounter.bytesRealWriteFlowControl(size);
                    perChannel.channelTrafficCounter.bytesRealWriteFlowControl(size);
                    perChannel.queueSize -= size;
                    queuesSize.addAndGet(-size);
                    ctx.write(newToSend.toSend, newToSend.promise);
                    perChannel.lastWriteTimestamp = now;
                } else {
                    perChannel.messagesQueue.addFirst(newToSend);
                    break;
                }
            }
            if (perChannel.messagesQueue.isEmpty()) {
                //如果 该channel的 待发送队列为空，则重置可写状态
                releaseWriteSuspended(ctx);
            }
        }
        ctx.flush();
    }

    @Override
    public String toString() {
        return new StringBuilder(340).append(super.toString())
            .append(" Write Channel Limit: ").append(writeChannelLimit)
            .append(" Read Channel Limit: ").append(readChannelLimit).toString();
    }
}
