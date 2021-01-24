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

package io.netty.buffer;

final class PoolSubpage<T> implements PoolSubpageMetric {

    final PoolChunk<T> chunk; //对应的chunk
    private final int memoryMapIdx; //chunk二叉树中第几个元素，肯定大于等于2048(因为PoolSubpage所属的节点肯定是最底层节点)，memoryMap中的id
    private final int runOffset; //本PoolSubpage所属的page在所属PoolChunk中的偏移量
    private final int pageSize; //页大小
    //位图 使用long型的 每一位 来用来标识对应子块的使用情况,0表示可用，1表示不可用。每个数组元素表示64个单元代码
    private final long[] bitmap;

    PoolSubpage<T> prev;
    PoolSubpage<T> next;

    boolean doNotDestroy; //是否可用 ,true表示可用，false表示不可用
    int elemSize; //PoolSubPage表示的内存大小
    private int maxNumElems; //本poolSubpage所属的page 总共有多少个PoolSubpage单位，每页的PoolSubpage的大小都相同
    private int bitmapLength; //位图大小，maxNumElems >>> 6，一个long有64bit，所以位图大小为 maxNumElems/6，
    private int nextAvail; //下一个可用的单位,下一个可用单位的位图索引，初始状态为0，申请之后设置为-1. 只有第一次跟free后nextAvail才可用。只有在free后再次设置为可用的单元索引。可以使用热块
    private int numAvail; //本poolSubpage所属的page 还有多少个可用PoolSubpage；

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /** Special constructor that creates a linked list head */
    PoolSubpage(int pageSize) {
        chunk = null;
        memoryMapIdx = -1;
        runOffset = -1;
        elemSize = -1;
        this.pageSize = pageSize;
        bitmap = null;
    }

    PoolSubpage(PoolSubpage<T> head, PoolChunk<T> chunk, int memoryMapIdx, int runOffset, int pageSize, int elemSize /*本次被分配的大小*/) {
        this.chunk = chunk;
        this.memoryMapIdx = memoryMapIdx;
        this.runOffset = runOffset;
        this.pageSize = pageSize;
        bitmap = new long[pageSize >>> 10]; // pageSize / 16 / 64  最小16个字节
        init(head, elemSize);
    }

    void  init(PoolSubpage<T> head, int elemSize) {
        doNotDestroy = true;
        this.elemSize = elemSize;
        if (elemSize != 0) {
            maxNumElems = numAvail = pageSize / elemSize;
            nextAvail = 0;
            bitmapLength = maxNumElems >>> 6;
            if ((maxNumElems & 63) != 0) {
                bitmapLength ++;
            }

            for (int i = 0; i < bitmapLength; i ++) {
                bitmap[i] = 0;
            }
        }
        addToPool(head);
    }

    /**
     * Returns the bitmap index of the subpage allocation.
     */
    //返回 对应的bitmap的索引
    long allocate() {
        if (elemSize == 0) {
            return toHandle(0);
        }

        //没有可用节点
        if (numAvail == 0 || !doNotDestroy) {
            return -1;
        }

        final int bitmapIdx = getNextAvail();//查找下一个单元索引
        int q = bitmapIdx >>> 6;//转为位图数组索引
        int r = bitmapIdx & 63;//保留最低的8位
        assert (bitmap[q] >>> r & 1) == 0;
        bitmap[q] |= 1L << r;//设置为1

        //没有可用元素，从poolSubpage池中移除
        if (-- numAvail == 0) {
            removeFromPool();
        }

        return toHandle(bitmapIdx);//对索引进行特化处理，防止与页索引冲突
    }

    /**
     * @return {@code true} if this subpage is in use.
     *         {@code false} if this subpage is not used by its chunk and thus it's OK to be released.
     */
    boolean free(PoolSubpage<T> head, int bitmapIdx) {
        if (elemSize == 0) {
            return true;
        }
        int q = bitmapIdx >>> 6;
        int r = bitmapIdx & 63;
        assert (bitmap[q] >>> r & 1) != 0;
        bitmap[q] ^= 1L << r;

        setNextAvail(bitmapIdx);

        if (numAvail ++ == 0) {
            addToPool(head);
            return true;
        }

        if (numAvail != maxNumElems) {
            //如果本PoolSubPage还有其他 内存块 被使用，直接返回
            return true;
        } else {
            // Subpage not in use (numAvail == maxNumElems)
            if (prev == next) {
                //如果本PoolSubPage是 PoolArena中唯一一个遗留下来的对应size的 PoolSubPage（prev和next都是 head几点），直接返回
                // Do not remove if this subpage is the only one left in the pool.
                return true;
            }

            // Remove this subpage from the pool if there are other subpages left in the pool.
            doNotDestroy = false;
            //如果如果本PoolSubPage不是 PoolArena中唯一一个遗留下来的对应size的 PoolSubPage，从链表中删除并释放空间
            removeFromPool();
            return false;
        }
    }

    //加入到可用链表头中
    private void addToPool(PoolSubpage<T> head) {
        assert prev == null && next == null;
        prev = head;
        next = head.next;
        next.prev = this;
        head.next = this;
    }

    //从可用链表中删除
    private void removeFromPool() {
        assert prev != null && next != null;
        prev.next = next;
        next.prev = prev;
        next = null;
        prev = null;
    }

    private void setNextAvail(int bitmapIdx) {
        nextAvail = bitmapIdx;
    }

    private int getNextAvail() {
        int nextAvail = this.nextAvail;
        if (nextAvail >= 0) { //大于等于0直接可用
            this.nextAvail = -1;
            return nextAvail;
        }
        return findNextAvail();//通常走这一步逻辑，只有第一次跟free后nextAvail才可用
    }
    //找到位图数组可用单元，是一个long类型，有[1,64]单元可用
    private int findNextAvail() {
        final long[] bitmap = this.bitmap;
        final int bitmapLength = this.bitmapLength;
        for (int i = 0; i < bitmapLength; i ++) {
            long bits = bitmap[i];
            if (~bits != 0) {
                return findNextAvail0(i, bits);
            }
        }
        return -1;
    }
    //在64的bit中找到一个可用的
    private int findNextAvail0(int i, long bits) {
        final int maxNumElems = this.maxNumElems;
        final int baseVal = i << 6;

        for (int j = 0; j < 64; j ++) {
            if ((bits & 1) == 0) {
                int val = baseVal | j;
                if (val < maxNumElems) {
                    return val;
                } else {
                    break;
                }
            }
            bits >>>= 1;
        }
        return -1;
    }

    private long toHandle(int bitmapIdx) {
        return 0x4000000000000000L | (long) bitmapIdx << 32 | memoryMapIdx;
    }


    @Override
    public String toString() {
        final boolean doNotDestroy;
        final int maxNumElems;
        final int numAvail;
        final int elemSize;
        synchronized (chunk.arena) {
            if (!this.doNotDestroy) {
                doNotDestroy = false;
                // Not used for creating the String.
                maxNumElems = numAvail = elemSize = -1;
            } else {
                doNotDestroy = true;
                maxNumElems = this.maxNumElems;
                numAvail = this.numAvail;
                elemSize = this.elemSize;
            }
        }

        if (!doNotDestroy) {
            return "(" + memoryMapIdx + ": not in use)";
        }

        return "(" + memoryMapIdx + ": " + (maxNumElems - numAvail) + '/' + maxNumElems +
                ", offset: " + runOffset + ", length: " + pageSize + ", elemSize: " + elemSize + ')';
    }

    @Override
    public int maxNumElements() {
        synchronized (chunk.arena) {
            return maxNumElems;
        }
    }

    @Override
    public int numAvailable() {
        synchronized (chunk.arena) {
            return numAvail;
        }
    }

    @Override
    public int elementSize() {
        synchronized (chunk.arena) {
            return elemSize;
        }
    }

    @Override
    public int pageSize() {
        return pageSize;
    }

    void destroy() {
        if (chunk != null) {
            chunk.destroy();
        }
    }
}
