package moe.fuqiuluo.mamu.driver

import java.nio.ByteBuffer
import java.nio.ByteOrder

object LocalMemoryOps {

    /**
     * 分配指定大小的内存
     * @param size 内存大小（字节）
     * @return 内存地址，失败返回0
     */
    fun alloc(size: Int): ULong {
        return nativeAlloc(size).toULong()
    }

    /**
     * 释放内存
     * @param address 内存地址
     * @param size 内存大小（字节）
     */
    fun free(address: ULong, size: Int) {
        nativeFree(address.toLong(), size)
    }

    /**
     * 读取内存字节
     * @param address 内存地址
     * @param size 读取大小（字节）
     * @return 字节数组
     */
    fun read(address: ULong, size: Int): ByteArray {
        return nativeRead(address.toLong(), size)
    }

    /**
     * 写入内存字节
     * @param address 内存地址
     * @param data 要写入的字节数组
     */
    fun write(address: ULong, data: ByteArray) {
        nativeWrite(address.toLong(), data)
    }

    /**
     * 读取Int值（小端序）
     */
    fun readInt(address: ULong): Int {
        val bytes = read(address, 4)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /**
     * 写入Int值（小端序）
     */
    fun writeInt(address: ULong, value: Int) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        write(address, bytes)
    }

    /**
     * 获取当前进程ID
     * @return 进程ID
     */
    fun getPid(): Int {
        return nativeGetPid()
    }

    /**
     * 获取当前一页的大小
     */
    fun getPageSize(): Int {
        return nativeGetPageSize()
    }

    /**
     * 获取静态全局变量 PRACTICE_GLOBAL_INSTANCE 的地址
     * 这个地址位于 .data/.bss 段，可以作为指针链的基址
     * @return 静态变量的地址
     */
    fun getStaticBase(): ULong {
        return nativeGetStaticBase().toULong()
    }

    /**
     * 设置静态全局变量 PRACTICE_GLOBAL_INSTANCE 的值
     * 用于构建指针链教程的基础结构
     * @param value 要写入的指针值
     */
    fun setStaticBase(value: ULong) {
        nativeSetStaticBase(value.toLong())
    }

    /**
     * 读取Long值（小端序，8字节指针）
     */
    fun readLong(address: ULong): Long {
        val bytes = read(address, 8)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long
    }

    /**
     * 写入Long值（小端序，8字节指针）
     */
    fun writeLong(address: ULong, value: Long) {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
        write(address, bytes)
    }

    // Native methods
    private external fun nativeAlloc(size: Int): Long
    private external fun nativeFree(address: Long, size: Int)
    private external fun nativeRead(address: Long, size: Int): ByteArray
    private external fun nativeWrite(address: Long, data: ByteArray)
    private external fun nativeGetPid(): Int
    private external fun nativeGetPageSize(): Int
    private external fun nativeGetStaticBase(): Long
    private external fun nativeSetStaticBase(value: Long)
}