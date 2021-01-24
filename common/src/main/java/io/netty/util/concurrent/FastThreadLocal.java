/*
 * Copyright 2014 The Netty Project
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
import io.netty.util.internal.ObjectCleaner;
import io.netty.util.internal.PlatformDependent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * A special variant of {@link ThreadLocal} that yields higher access performance when accessed from a
 * {@link FastThreadLocalThread}.
 *ThreadLocal的一种特殊变体，使用FastThreadLocalThread访问时可以产生更高的访问性能
 * <p>
 * Internally, a {@link FastThreadLocal} uses a constant index in an array, instead of using hash code and hash table,
 * to look for a variable.  Although seemingly very subtle, it yields slight performance advantage over using a hash
 * table, and it is useful when accessed frequently.
 * </p><p>
 * FastThreadLocal在内部使用了数组中的常量索引来寻找一个变量，而不是使用哈希码和哈希表，
   *虽然看似非常微小，但它比使用哈希产生了轻微的性能优势，经常访问时很有用
 * To take advantage of this thread-local variable, your thread must be a {@link FastThreadLocalThread} or its subtype.
 * By default, all threads created by {@link DefaultThreadFactory} are {@link FastThreadLocalThread} due to this reason.
 * </p><p>
 *  要利用此线程局部变量，您的线程必须是{@link FastThreadLocalThread}或其子类型
 *  默认情况下，{@link DefaultThreadFactory}创建的所有线程都是{@link FastThreadLocalThread}，就是因为这个原因。
 * Note that the fast path is only possible on threads that extend {@link FastThreadLocalThread}, because it requires
 * a special field to store the necessary state.  An access by any other kind of thread falls back to a regular
 * {@link ThreadLocal}.
 * 请注意，快速路径仅适用于继承{@link FastThreadLocalThread}的线程，因为它需要
   *一个特定的字段来存储必要状态。 任何其他类型的线程访问都会回归到常规的ThreadLocal
 * </p>
 *
 * @param <V> the type of the thread-local variable
 * @see ThreadLocal
 */
public class FastThreadLocal<V> {

    //本index 在对应的 InternalThreadLocalMap 的元素列表中对应的value是一个set(Set<FastThreadLocal<?>>)，保存着 需要被删除 元素
    private static final int variablesToRemoveIndex = InternalThreadLocalMap.nextVariableIndex();

    /**
     * Removes all {@link FastThreadLocal} variables bound to the current thread.  This operation is useful when you
     * are in a container environment, and you don't want to leave the thread local variables in the threads you do not
     * manage.
     */
    //移除 被remove的元素
    //首先获取当前线程map，然后获取 可清理FastThreadLocal的Set，将 Set 转成数组，
    // 遍历数组，调用 ftl 的 remove 方法。最后，删除线程中 的 map 属性。
    public static void removeAll() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return;
        }

        try {
            Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
            if (v != null && v != InternalThreadLocalMap.UNSET) {
                @SuppressWarnings("unchecked")
                Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
                FastThreadLocal<?>[] variablesToRemoveArray =
                        variablesToRemove.toArray(new FastThreadLocal[variablesToRemove.size()]);
                for (FastThreadLocal<?> tlv: variablesToRemoveArray) {
                    tlv.remove(threadLocalMap);
                }
            }
        } finally {
            InternalThreadLocalMap.remove();
        }
    }

    /**
     * Returns the number of thread local variables bound to the current thread.
     */
    public static int size() {
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.getIfSet();
        if (threadLocalMap == null) {
            return 0;
        } else {
            return threadLocalMap.size();
        }
    }

    /**
     * Destroys the data structure that keeps all {@link FastThreadLocal} variables accessed from
     * non-{@link FastThreadLocalThread}s.  This operation is useful when you are in a container environment, and you
     * do not want to leave the thread local variables in the threads you do not manage.  Call this method when your
     * application is being unloaded from the container.
     */
    public static void destroy() {
        InternalThreadLocalMap.destroy();
    }

    //将 FastThreadLocal 被清除的对象保存到一个 Set 中，静态方法 removeAll 就需要使用到这个 Set，
    // 可以快速的删除线程 Map 里的所有 被清除的 Value。如果不使用 Set，那么就需要遍历 InternalThreadLocalMap，性能不高。
    //todo
    @SuppressWarnings("unchecked")
    private static void addToVariablesToRemove(InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {
        // variablesToRemoveIndex变量是 static final 的，因此通常是 0
        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);
        Set<FastThreadLocal<?>> variablesToRemove;
        if (v == InternalThreadLocalMap.UNSET || v == null) {
            // 创建一个基于 IdentityHashMap 的 Set，泛型是 FastThreadLocal
            variablesToRemove = Collections.newSetFromMap(new IdentityHashMap<FastThreadLocal<?>, Boolean>());
            // 将这个 Set 放到这个 Map 数组的下标 0 处
            threadLocalMap.setIndexedVariable(variablesToRemoveIndex, variablesToRemove);
        } else {
            // 如果拿到的不是 UNSET ，说明这是第二次操作了，因此可以强转为 Set
            variablesToRemove = (Set<FastThreadLocal<?>>) v;
        }

        // 最后的目的就是将 FastThreadLocal 放置到 Set 中
        variablesToRemove.add(variable);
    }

    private static void removeFromVariablesToRemove(
            InternalThreadLocalMap threadLocalMap, FastThreadLocal<?> variable) {

        Object v = threadLocalMap.indexedVariable(variablesToRemoveIndex);

        if (v == InternalThreadLocalMap.UNSET || v == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Set<FastThreadLocal<?>> variablesToRemove = (Set<FastThreadLocal<?>>) v;
        variablesToRemove.remove(variable);
    }

    //元素在数组中的索引，通过 InternalThreadLocalMap.inde+1获取到
    private final int index;

    //本index 在对应的 InternalThreadLocalMap 的元素列表中对应的value是boolean,表示是否已经为当前threadlocal注册过清理器
    //通过 InternalThreadLocalMap.inde+1获取到
    private final int cleanerFlagIndex;

    public FastThreadLocal() {
        index = InternalThreadLocalMap.nextVariableIndex();
        cleanerFlagIndex = InternalThreadLocalMap.nextVariableIndex();
    }

    /**
     * Returns the current value for the current thread
     */
    @SuppressWarnings("unchecked")
    //获取当前线程的map，然后根据 ftl 的 index 获取 value，然后返回，
    // 如果是空对象，也就是没有设置，则通过 initialize 返回，initialize 方法会将返回值设置到 map 的槽位中，并放进 Set 中
    public final V get() {
        //获取到线程对应的InternalThreadLocalMap对象
        InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        V value = initialize(threadLocalMap);
        registerCleaner(threadLocalMap);
        return value;
    }

    //将本FastThreadLocal注册到一个 清理线程 中，当 (thread 对象)被 gc 的时候，则会自动清理掉 ftl，防止 JDK 的内存泄漏问题
    // 注册清理任务的原因：
    //当我们在一个非 Netty 线程池创建的线程中使用 ftl 的时候，Netty 会注册一个垃圾清理线程
    // （因为 Netty 线程池创建的线程最终都会执行 removeAll 方法，不会出现内存泄漏） ，
    // 用于清理这个线程这个 ftl 变量，从上面的代码中，我们知道，非 Netty 线程如果使用 ftl，
    // Netty 仍然会借助 JDK 的 ThreadLocal，只是只借用一个槽位，放置 Netty 的 Map，
    // Map 中再放置 Netty 的 ftl 。所以，在使用线程池的情况下可能会出现内存泄漏。
    // Netty 为了解决这个问题，在每次使用新的 ftl 的时候，都将这个 ftl 注册到和线程对象绑定到一个 GC 引用上，
    // 当这个线程对象被回收的时候，也会顺便清理掉他的 Map 中的 所有 ftl，
    private void registerCleaner(final InternalThreadLocalMap threadLocalMap) {
        //获取当前线程，如果当前线程是 FastThreadLocalThread 类型 且 cleanupFastThreadLocals 是 true，
        // 则返回 true，直接return。也就是说，Netty 线程池里面创建的线程都符合这条件，只有用户自定义的线程池不符合。
        Thread current = Thread.currentThread();
        if (FastThreadLocalThread.willCleanupFastThreadLocals(current) ||
                //是否已经注册过清理器，防止重复注册
            threadLocalMap.indexedVariable(cleanerFlagIndex) != InternalThreadLocalMap.UNSET) {
            return;
        }
        // removeIndexedVariable(cleanerFlagIndex) isn't necessary because the finally cleanup is tied to the lifetime
        // of the thread, and this Object will be discarded if the associated thread is GCed.
        threadLocalMap.setIndexedVariable(cleanerFlagIndex, Boolean.TRUE);

        // We will need to ensure we will trigger remove(InternalThreadLocalMap) so everything will be released
        // and FastThreadLocal.onRemoval(...) will be called.
        //注册清理方法，在 本线程 结束，本线程对象被 gc时，清理 本线程对应的 threadLocalMap
        ObjectCleaner.register(current, new Runnable() {
            @Override
            public void run() {
                remove(threadLocalMap);

                // It's fine to not call InternalThreadLocalMap.remove() here as this will only be triggered once
                // the Thread is collected by GC. In this case the ThreadLocal will be gone away already.
            }
        });
    }

    /**
     * Returns the current value for the specified thread local map.
     * The specified thread local map must be for the current thread.
     */
    @SuppressWarnings("unchecked")
    public final V get(InternalThreadLocalMap threadLocalMap) {
        Object v = threadLocalMap.indexedVariable(index);
        if (v != InternalThreadLocalMap.UNSET) {
            return (V) v;
        }

        return initialize(threadLocalMap);
    }

    private V initialize(InternalThreadLocalMap threadLocalMap) {
        V v = null;
        try {
            v = initialValue();
        } catch (Exception e) {
            PlatformDependent.throwException(e);
        }

        threadLocalMap.setIndexedVariable(index, v);
        //将本对象放置到移除列表中
        addToVariablesToRemove(threadLocalMap, this);
        return v;
    }

    /**
     * Set the value for the current thread.
     */
    /**
     * 1.判断设置的 value 值是否是InternalThreadLocalMap.UNSET，如果是，则调用 remove 方法。
        2.如果不是，则获取道当前线程的 InternalThreadLocalMap。然后将该 FastThreadLocal 对应的
        index 下标的 value 替换成新的 value。老的 value 设置成InternalThreadLocalMap.UNSET。
     * @param value
     */
    public final void set(V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            ////获取到线程对应的InternalThreadLocalMap对象
            InternalThreadLocalMap threadLocalMap = InternalThreadLocalMap.get();
            //如果该槽位没有值，注册一个 清理器
            if (setKnownNotUnset(threadLocalMap, value)) {
                registerCleaner(threadLocalMap);
            }
        } else {
            remove();
        }
    }

    /**
     * Set the value for the specified thread local map. The specified thread local map must be for the current thread.
     */
    public final void set(InternalThreadLocalMap threadLocalMap, V value) {
        if (value != InternalThreadLocalMap.UNSET) {
            setKnownNotUnset(threadLocalMap, value);
        } else {
            remove(threadLocalMap);
        }
    }

    /**
     * @return see {@link InternalThreadLocalMap#setIndexedVariable(int, Object)}.
     */
    //是设置一个值，但不是 unset，也就是那个空对象。通过
    // threadLocalMap.setIndexedVariable(index, value) 进行设置。如果返回 true，
    // 则调用 addToVariablesToRemove(threadLocalMap, this)
    private boolean setKnownNotUnset(InternalThreadLocalMap threadLocalMap, V value) {
        if (threadLocalMap.setIndexedVariable(index, value)) {
            //如果原槽位没有值(真正新增了对象)，调用addToVariablesToRemove
            addToVariablesToRemove(threadLocalMap, this);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     */
    public final boolean isSet() {
        return isSet(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Returns {@code true} if and only if this thread-local variable is set.
     * The specified thread local map must be for the current thread.
     */
    public final boolean isSet(InternalThreadLocalMap threadLocalMap) {
        return threadLocalMap != null && threadLocalMap.isIndexedVariableSet(index);
    }
    /**
     * Sets the value to uninitialized; a proceeding call to get() will trigger a call to initialValue().
     */
    public final void remove() {
        remove(InternalThreadLocalMap.getIfSet());
    }

    /**
     * Sets the value to uninitialized for the specified thread local map;
     * a proceeding call to get() will trigger a call to initialValue().
     * The specified thread local map must be for the current thread.
     */
    //移除 本ThreadLocal
    @SuppressWarnings("unchecked")
    public final void remove(InternalThreadLocalMap threadLocalMap) {
        if (threadLocalMap == null) {
            return;
        }

        Object v = threadLocalMap.removeIndexedVariable(index);
        //从gc清理列表中 移除本对象(因为已经被删除了)
        removeFromVariablesToRemove(threadLocalMap, this);

        if (v != InternalThreadLocalMap.UNSET) {
            try {
                //调用 onRemoval钩子
                onRemoval((V) v);
            } catch (Exception e) {
                PlatformDependent.throwException(e);
            }
        }
    }

    /**
     * Returns the initial value for this thread-local variable.
     */
    protected V initialValue() throws Exception {
        return null;
    }

    /**
     * Invoked when this thread local variable is removed by {@link #remove()}.
     */
    //remove的钩子
    protected void onRemoval(@SuppressWarnings("UnusedParameters") V value) throws Exception { }
}
