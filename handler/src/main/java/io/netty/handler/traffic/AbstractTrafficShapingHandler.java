/*
 * Copyright 2011 The Netty Project
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
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * <p>AbstractTrafficShapingHandler allows to limit the global bandwidth
 * (see {@link GlobalTrafficShapingHandler}) or per session
 * bandwidth (see {@link ChannelTrafficShapingHandler}), as traffic shaping.
 * It allows you to implement an almost real time monitoring of the bandwidth using
 * the monitors from {@link TrafficCounter} that will call back every checkInterval
 * the method doAccounting of this handler.</p>
 *
 * <p>If you want for any particular reasons to stop the monitoring (accounting) or to change
 * the read/write limit or the check interval, several methods allow that for you:</p>
 * <ul>
 * <li><tt>configure</tt> allows you to change read or write limits, or the checkInterval</li>
 * <li><tt>getTrafficCounter</tt> allows you to have access to the TrafficCounter and so to stop
 * or start the monitoring, to change the checkInterval directly, or to have access to its values.</li>
 * </ul>
 */
/*
允许限制全局的带宽（见GlobalTrafficShapingHandler）或者每个session的带宽（见ChannelTrafficShapingHandler）作为流量整形。
它允许你使用TrafficCounter来实现几乎实时的带宽监控，TrafficCounter会在每个检测间期
（checkInterval）调用这个处理器的doAccounting方法。
 */
public abstract class AbstractTrafficShapingHandler extends ChannelDuplexHandler {
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(AbstractTrafficShapingHandler.class);
    /**
     * Default delay between two checks: 1s
     */
    //默认检测时间间隔，1s
    public static final long DEFAULT_CHECK_INTERVAL = 1000;

   /**
    * Default max delay in case of traffic shaping
    * (during which no communication will occur).
    * Shall be less than TIMEOUT. Here half of "standard" 30s
    */
    public static final long DEFAULT_MAX_TIME = 15000;

    /**
     * Default max size to not exceed in buffer (write only).
     */
    //写操作缓冲区中 最大保存字节数，超出该字节数 会 触发
    static final long DEFAULT_MAX_SIZE = 4 * 1024 * 1024L;

    /**
     * Default minimal time to wait
     */
    // ms，最小等待时间，如果 流控需要的等待时间 小于10ms，则不等待直接 写入或者读取
    static final long MINIMAL_WAIT = 10;

    /**
     * Traffic Counter
     */
    //流量统计器(有全局流量 统计器，每个channel还有各自的流量 统计器)
    protected TrafficCounter trafficCounter;

    /**
     * Limit in B/s to apply to write
     */
    //写速率 /s
    private volatile long writeLimit;

    /**
     * Limit in B/s to apply to read
     */
    //读速率 /s
    private volatile long readLimit;

    /**
     * Max delay in wait
     */
    //最大等待时间,默认15s
    protected volatile long maxTime = DEFAULT_MAX_TIME; // default 15 s

    /**
     * Delay between two performance snapshots
     */
    // 流量检测 定时任务 检测的时间间隔
    protected volatile long checkInterval = DEFAULT_CHECK_INTERVAL; // default 1 s

    //false表示读暂停未启用
    static final AttributeKey<Boolean> READ_SUSPENDED = AttributeKey
            .valueOf(AbstractTrafficShapingHandler.class.getName() + ".READ_SUSPENDED");
    //重启 读操作 的task(在读暂停后 设置到 channel的属性中)
    static final AttributeKey<Runnable> REOPEN_TASK = AttributeKey.valueOf(AbstractTrafficShapingHandler.class
            .getName() + ".REOPEN_TASK");

    /**
     * Max time to delay before proposing to stop writing new objects from next handlers
     */
    //写操作的 最大延迟，默认4s（延迟）
    volatile long maxWriteDelay = 4 * DEFAULT_CHECK_INTERVAL; // default 4 s
    /**
     * Max size in the list before proposing to stop writing new objects from next handlers
     */
    //写操作 最大单次写的 字节数
    volatile long maxWriteSize = DEFAULT_MAX_SIZE; // default 4MB

    /**
     * Rank in UserDefinedWritability (1 for Channel, 2 for Global TrafficShapingHandler).
     * Set in final constructor. Must be between 1 and 31
     */
    final int userDefinedWritabilityIndex;

    /**
     * Default value for Channel UserDefinedWritability index
     */
    static final int CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 1;

    /**
     * Default value for Global UserDefinedWritability index
     */
    static final int GLOBAL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 2;

    /**
     * Default value for GlobalChannel UserDefinedWritability index
     */
    static final int GLOBALCHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX = 3;

    /**
     * @param newTrafficCounter
     *            the TrafficCounter to set
     */
    void setTrafficCounter(TrafficCounter newTrafficCounter) {
        trafficCounter = newTrafficCounter;
    }

    /**
     * @return the index to be used by the TrafficShapingHandler to manage the user defined writability.
     *              For Channel TSH it is defined as {@value #CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX},
     *              for Global TSH it is defined as {@value #GLOBAL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX},
     *              for GlobalChannel TSH it is defined as
     *              {@value #GLOBALCHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX}.
     */
    protected int userDefinedWritabilityIndex() {
        return CHANNEL_DEFAULT_USER_DEFINED_WRITABILITY_INDEX;
    }

    /**
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     * @param maxTime
     *            The maximum delay to wait in case of traffic excess.
     *            Must be positive.
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit, long checkInterval, long maxTime) {
        if (maxTime <= 0) {
            throw new IllegalArgumentException("maxTime must be positive");
        }

        userDefinedWritabilityIndex = userDefinedWritabilityIndex();
        this.writeLimit = writeLimit;
        this.readLimit = readLimit;
        this.checkInterval = checkInterval;
        this.maxTime = maxTime;
    }

    /**
     * Constructor using default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     * @param writeLimit
     *            0 or a limit in bytes/s
     * @param readLimit
     *            0 or a limit in bytes/s
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit, long checkInterval) {
        this(writeLimit, readLimit, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default Check Interval value of {@value #DEFAULT_CHECK_INTERVAL} ms and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     *
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     */
    protected AbstractTrafficShapingHandler(long writeLimit, long readLimit) {
        this(writeLimit, readLimit, DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using NO LIMIT, default Check Interval value of {@value #DEFAULT_CHECK_INTERVAL} ms and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     */
    protected AbstractTrafficShapingHandler() {
        this(0, 0, DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using NO LIMIT and
     * default max time as delay allowed value of {@value #DEFAULT_MAX_TIME} ms.
     *
     * @param checkInterval
     *            The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed.
     */
    protected AbstractTrafficShapingHandler(long checkInterval) {
        this(0, 0, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Change the underlying limitations and check interval.
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    /*
    允许你改变读或写的限制，或者检测间期（checkInterval）
     */
    /*
    配置新的写限制、读限制、检测间期。该方法会尽最大努力进行此更改，
    这意味着已经被延迟进行的流量将不会使用新的配置，它仅用于新的流量中。
     */
    public void configure(long newWriteLimit, long newReadLimit,
            long newCheckInterval) {
        configure(newWriteLimit, newReadLimit);
        configure(newCheckInterval);
    }

    /**
     * Change the underlying limitations.
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param newWriteLimit The new write limit (in bytes)
     * @param newReadLimit The new read limit (in bytes)
     */
    public void configure(long newWriteLimit, long newReadLimit) {
        writeLimit = newWriteLimit;
        readLimit = newReadLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * Change the check interval.
     *
     * @param newCheckInterval The new check interval (in milliseconds)
     */
    public void configure(long newCheckInterval) {
        checkInterval = newCheckInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * @return the writeLimit
     */
    public long getWriteLimit() {
        return writeLimit;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param writeLimit the writeLimit to set
     */
    public void setWriteLimit(long writeLimit) {
        this.writeLimit = writeLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * @return the readLimit
     */
    public long getReadLimit() {
        return readLimit;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param readLimit the readLimit to set
     */
    public void setReadLimit(long readLimit) {
        this.readLimit = readLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(TrafficCounter.milliSecondFromNano());
        }
    }

    /**
     * @return the checkInterval
     */
    public long getCheckInterval() {
        return checkInterval;
    }

    /**
     * @param checkInterval the interval in ms between each step check to set, default value being 1000 ms.
     */
    public void setCheckInterval(long checkInterval) {
        this.checkInterval = checkInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param maxTime
     *            Max delay in wait, shall be less than TIME OUT in related protocol.
     *            Must be positive.
     */
    public void setMaxTimeWait(long maxTime) {
        if (maxTime <= 0) {
            throw new IllegalArgumentException("maxTime must be positive");
        }
        this.maxTime = maxTime;
    }

    /**
     * @return the max delay in wait to prevent TIME OUT
     */
    public long getMaxTimeWait() {
        return maxTime;
    }

    /**
     * @return the maxWriteDelay
     */
    public long getMaxWriteDelay() {
        return maxWriteDelay;
    }

    /**
     * <p>Note the change will be taken as best effort, meaning
     * that all already scheduled traffics will not be
     * changed, but only applied to new traffics.</p>
     * <p>So the expected usage of this method is to be used not too often,
     * accordingly to the traffic shaping configuration.</p>
     *
     * @param maxWriteDelay the maximum Write Delay in ms in the buffer allowed before write suspension is set.
     *              Must be positive.
     */
    public void setMaxWriteDelay(long maxWriteDelay) {
        if (maxWriteDelay <= 0) {
            throw new IllegalArgumentException("maxWriteDelay must be positive");
        }
        this.maxWriteDelay = maxWriteDelay;
    }

    /**
     * @return the maxWriteSize default being {@value #DEFAULT_MAX_SIZE} bytes.
     */
    public long getMaxWriteSize() {
        return maxWriteSize;
    }

    /**
     * <p>Note that this limit is a best effort on memory limitation to prevent Out Of
     * Memory Exception. To ensure it works, the handler generating the write should
     * use one of the way provided by Netty to handle the capacity:</p>
     * <p>- the {@code Channel.isWritable()} property and the corresponding
     * {@code channelWritabilityChanged()}</p>
     * <p>- the {@code ChannelFuture.addListener(new GenericFutureListener())}</p>
     *
     * @param maxWriteSize the maximum Write Size allowed in the buffer
     *            per channel before write suspended is set,
     *            default being {@value #DEFAULT_MAX_SIZE} bytes.
     */
    public void setMaxWriteSize(long maxWriteSize) {
        this.maxWriteSize = maxWriteSize;
    }

    /**
     * Called each time the accounting is computed from the TrafficCounters.
     * This method could be used for instance to implement almost real time accounting.
     *
     * @param counter
     *            the TrafficCounter that computes its performance
     */
    //每次 流量监控 计时器 定时任务 计算完流量后调用
    protected void doAccounting(TrafficCounter counter) {
        // NOOP by default
    }

    /**
     * Class to implement setReadable at fix time
     */
    //重启读操作的定时任务。该定时任务总会实现：
        /*
        重启读操作的定时任务。该定时任务总会实现：
a) 如果Channel的autoRead为false，并且AbstractTrafficShapingHandler的READ_SUSPENDED属性设置为null或false（说明读暂停未启用或开启），则直接将READ_SUSPENDED属性设置为false。
b) 否则，如果Channel的autoRead为true，或者READ_SUSPENDED属性的值为true（说明读暂停开启了），则将READ_SUSPENDED属性设置为false，并将Channel的autoRead标识为true（该操作底层会将该Channel的OP_READ事件重新注册为感兴趣的事件，这样Selector就会监听该Channel的读就绪事件了），最后触发一次Channel的read操作。
也就说，若“读操作”为“开启”状态（READ_SUSPENDED为null或false）的情况下，Channel的autoRead是保持Channel原有的配置，此时并不会做什么操作。但当“读操作”从“暂停”状态（READ_SUSPENDED为true）转为“开启”状态（READ_SUSPENDED为false）时，则会将Channel的autoRead标志为true，并将“读操作”设置为“开启”状态（READ_SUSPENDED为false）。
         */
    static final class ReopenReadTimerTask implements Runnable {
        final ChannelHandlerContext ctx;
        ReopenReadTimerTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        /*
        若“读操作”为“开启”状态（READ_SUSPENDED为null或false）的情况下，Channel的autoRead是保持
                Channel原有的配置，此时并不会做什么操作。但当“读操作”从“暂停”状态（READ_SUSPENDED为true）
                转为“开启”状态（READ_SUSPENDED为false）时，则会将Channel的autoRead标志为true（该操作底层会将该Channel的OP_READ事件
                重新注册为感兴趣的事件，这样Selector就会监听该Channel的读就绪事件了），
                并将“读操作”设置为“开启”状态（READ_SUSPENDED为false）。
         */
        @Override
        public void run() {
            Channel channel = ctx.channel();
            ChannelConfig config = channel.config();
            if (!config.isAutoRead() && isHandlerActive(ctx)) {
                // If AutoRead is False and Active is True, user make a direct setAutoRead(false)
                // Then Just reset the status
                if (logger.isDebugEnabled()) {
                    logger.debug("Not unsuspend: " + config.isAutoRead() + ':' +
                            isHandlerActive(ctx));
                }
                /*
                如果Channel的autoRead为false，并且AbstractTrafficShapingHandler的READ_SUSPENDED属性设置为null或false
                （说明读暂停未启用，也就是说不是由于本handler导致的读暂停），则直接将READ_SUSPENDED属性设置为false
                */
                channel.attr(READ_SUSPENDED).set(false);
            } else {
                /*
                否则，如果Channel的autoRead为true，或者READ_SUSPENDED属性的值为true（说明读暂停开启了），
                则将READ_SUSPENDED属性设置为false，并将Channel的autoRead标识为true（该操作底层会将该Channel的OP_READ事件
                重新注册为感兴趣的事件，这样Selector就会监听该Channel的读就绪事件了），触发一次Channel的read操作。(也就是说重新开启读)
                 */
                // Anything else allows the handler to reset the AutoRead
                if (logger.isDebugEnabled()) {
                    if (config.isAutoRead() && !isHandlerActive(ctx)) {
                        logger.debug("Unsuspend: " + config.isAutoRead() + ':' +
                                isHandlerActive(ctx));
                    } else {
                        logger.debug("Normal unsuspend: " + config.isAutoRead() + ':'
                                + isHandlerActive(ctx));
                    }
                }
                channel.attr(READ_SUSPENDED).set(false);
                config.setAutoRead(true);
                channel.read();
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Unsuspend final status => " + config.isAutoRead() + ':'
                        + isHandlerActive(ctx));
            }
        }
    }

    /**
     * Release the Read suspension
     */
    // 开启读操作
    void releaseReadSuspended(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        channel.attr(READ_SUSPENDED).set(false);
        channel.config().setAutoRead(true);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
        //计算本次读取到的消息的字节数。
        long size = calculateSize(msg);

        long now = TrafficCounter.milliSecondFromNano();
        /*
        只有当计算出的下一次读操作的等待时间大于了MINIMAL_WAIT(10毫秒)，并且当前Channel是自动读取的，
        且“读操作”处于“开启”状态时，才会去暂停读操作.
        ，而暂停读操作主要需要完成三件事：
        [1]将Channel的autoRead标识设置为false，(底层使得OP_READ会从感兴趣的事件中移除)，
        这样Selector就会不会监听这个Channel的读就绪事件了；
        [2]将“读操作”状态位设置为“暂停”（READ_SUSPENDED为true）；
        [3]将重启开启“读操作”的操作封装为一个task，在延迟wait时间后执行。
        当你将得Channel的autoRead都会被设置为false时，Netty底层就不会再去执行读操作了，
        也就是说，这时如果有数据过来，会先放入到内核的接收缓冲区，只有我们执行读操作的时候数据
        才会从内核缓冲区读取到用户缓冲区中。而对于TCP协议来说，你不要担心一次内核缓冲区会溢出。
        因为如果应用进程一直没有读取，接收缓冲区满了之后，发生的动作是：通知对端TCP协议中的窗口关闭。
        这个便是滑动窗口的实现。保证TCP套接口接收缓冲区不会溢出，从而保证了TCP是可靠传输。
        因为对方不允许发出超过所通告窗口大小的数据。 这就是TCP的流量控制，
        如果对方无视窗口大小而发出了超过窗口大小的数据，则接收方TCP将丢弃它。
         */
        if (size > 0) {
            /*
        如果读取到的字节数大于0，则根据数据的大小、设定的readLimit、最大延迟时间等计算
        （『long wait = trafficCounter.readTimeToWait(size, readLimit, maxTime, now);』）
        得到下一次开启读操作需要的延迟时间（距当前时间而言）wait(毫秒)。
         */
            // compute the number of ms to wait before reopening the channel
            long wait = trafficCounter.readTimeToWait(size, readLimit, maxTime, now);
            // 重新计算 等待时间
            wait = checkWaitReadTime(ctx, wait, now);
            /*
            如果a）wait >= MINIMAL_WAIT(10毫秒)。
             */
            if (wait >= MINIMAL_WAIT) { // At least 10ms seems a minimal
                // time in order to try to limit the traffic
                // Only AutoRead AND HandlerActive True means Context Active
                Channel channel = ctx.channel();
                ChannelConfig config = channel.config();
                if (logger.isDebugEnabled()) {
                    logger.debug("Read suspend: " + wait + ':' + config.isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                //如果 当前Channel为自动读取（即，autoRead为true）以及 并且当前的READ_SUSPENDED标识为null或false
                // （即，读操作未被暂停）
                //如果 之前已经被 读暂停，此时 autoread也是false
                if (config.isAutoRead() && isHandlerActive(ctx)) {
                    //那么将Channel的autoRead设置为false（该操作底层会将该Channel的OP_READ事件从感兴趣的事件中移除，
                    // 这样Selector就不会监听该Channel的读就绪事件了）
                    config.setAutoRead(false);
                    //并且将READ_SUSPENDED标识为true（说明，接下来的读操作会被暂停）
                    channel.attr(READ_SUSPENDED).set(true);
                    // Create a Runnable to reactive the read if needed. If one was create before it will just be
                    // reused to limit object creation
                    //设置 重新开启 channel的任务
                    Attribute<Runnable> attr = channel.attr(REOPEN_TASK);
                    Runnable reopenTask = attr.get();
                    if (reopenTask == null) {
                        reopenTask = new ReopenReadTimerTask(ctx);
                        attr.set(reopenTask);
                    }
                    //将“重新开启读操作“封装为一个任务，让入Channel所注册NioEventLoop的定时任务队列中
                    // （延迟wait时间后执行）。
                    ctx.executor().schedule(reopenTask, wait, TimeUnit.MILLISECONDS);
                    if (logger.isDebugEnabled()) {
                        logger.debug("Suspend final status => " + config.isAutoRead() + ':'
                                + isHandlerActive(ctx) + " will reopened at: " + wait);
                    }
                }
            }
        }
        informReadOperation(ctx, now);
        //将当前的消息发送给ChannelPipeline中的下一个ChannelInboundHandler。
        ctx.fireChannelRead(msg);
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel.
     * @param wait the wait delay computed in ms
     * @param now the relative now time in ms
     * @return the wait to use according to the context
     */
    long checkWaitReadTime(final ChannelHandlerContext ctx, long wait, final long now) {
        // no change by default
        return wait;
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel.
     * @param now the relative now time in ms
     */
    void informReadOperation(final ChannelHandlerContext ctx, final long now) {
        // default noop
    }

    //读暂停 是否已经开启
    protected static boolean isHandlerActive(ChannelHandlerContext ctx) {
        Boolean suspended = ctx.channel().attr(READ_SUSPENDED).get();
        return suspended == null || Boolean.FALSE.equals(suspended);
    }

    @Override
    public void read(ChannelHandlerContext ctx) {
        if (isHandlerActive(ctx)) {
            // For Global Traffic (and Read when using EventLoop in pipeline) : check if READ_SUSPENDED is False
            ctx.read();
        }
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
            throws Exception {
        //计算待写出的数据大小
        long size = calculateSize(msg);
        long now = TrafficCounter.milliSecondFromNano();
        //如果待写出数据的字节数大于0
        if (size > 0) {
            //根据数据大小、设置的writeLimit、最大延迟时间等计算得到本次写操作需要的延迟时间（距当前时间而言）wait(毫秒)。
            // compute the number of ms to wait before continue with the channel
            long wait = trafficCounter.writeTimeToWait(size, writeLimit, maxTime, now);
            // 如果wait >= MINIMAL_WAIT（10毫秒）
            if (wait >= MINIMAL_WAIT) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Write suspend: " + wait + ':' + ctx.channel().config().isAutoRead() + ':'
                            + isHandlerActive(ctx));
                }
                //wait即为延迟时间
                submitWrite(ctx, msg, size, wait, now, promise);
                return;
            }
        }
        //注意这里传递的延迟时间为0
        // to maintain order of write
        submitWrite(ctx, msg, size, 0, now, promise);
    }

    @Deprecated
    protected void submitWrite(final ChannelHandlerContext ctx, final Object msg,
            final long delay, final ChannelPromise promise) {
        submitWrite(ctx, msg, calculateSize(msg),
                delay, TrafficCounter.milliSecondFromNano(), promise);
    }

    abstract void submitWrite(
            ChannelHandlerContext ctx, Object msg, long size, long delay, long now, ChannelPromise promise);

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        setUserDefinedWritability(ctx, true);
        super.channelRegistered(ctx);
    }

    // 将ChannelOutboundBuffer中的unwritable属性值相应的标志位置位。
    void setUserDefinedWritability(ChannelHandlerContext ctx, boolean writable) {
        ChannelOutboundBuffer cob = ctx.channel().unsafe().outboundBuffer();
        if (cob != null) {
            cob.setUserDefinedWritability(userDefinedWritabilityIndex, writable);
        }
    }

    /**
     * Check the writability according to delay and size for the channel.
     * Set if necessary setUserDefinedWritability status.
     * @param delay the computed delay
     * @param queueSize the current queueSize
     */
    void checkWriteSuspend(ChannelHandlerContext ctx, long delay, long queueSize) {
        //检查单个Channel待发送的数据包是否超过了maxWriteSize（默认4M），或者延迟时间是否超过了maxWriteDelay（默认4s）。
        if (queueSize > maxWriteSize || delay > maxWriteDelay) { //如果是的话
            //如果满足上述条件会将ChannelOutboundBuffer中的unwritable属性值的相应标志位置位
            // （isWritable方法返回false。
            //以及会在unwritable从0到非0间变化时触发ChannelWritabilityChanged事件）
            setUserDefinedWritability(ctx, false);
        }
    }
    /**
     * Explicitly release the Write suspended status.
     */
    //将ChannelOutboundBuffer中的unwritable属性值相应的标志位重置。
    void releaseWriteSuspended(ChannelHandlerContext ctx) {
        setUserDefinedWritability(ctx, true);
    }

    /**
     * @return the current TrafficCounter (if
     *         channel is still connected)
     */
    public TrafficCounter trafficCounter() {
        return trafficCounter;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(290)
            .append("TrafficShaping with Write Limit: ").append(writeLimit)
            .append(" Read Limit: ").append(readLimit)
            .append(" CheckInterval: ").append(checkInterval)
            .append(" maxDelay: ").append(maxWriteDelay)
            .append(" maxSize: ").append(maxWriteSize)
            .append(" and Counter: ");
        if (trafficCounter != null) {
            builder.append(trafficCounter);
        } else {
            builder.append("none");
        }
        return builder.toString();
    }

    /**
     * Calculate the size of the given {@link Object}.
     *
     * This implementation supports {@link ByteBuf} and {@link ByteBufHolder}. Sub-classes may override this.
     * @param msg the msg for which the size should be calculated.
     * @return size the size of the msg or {@code -1} if unknown.
     */
    //获取msg的size大小
    protected long calculateSize(Object msg) {
        if (msg instanceof ByteBuf) {
            return ((ByteBuf) msg).readableBytes();
        }
        if (msg instanceof ByteBufHolder) {
            return ((ByteBufHolder) msg).content().readableBytes();
        }
        return -1;
    }
}
