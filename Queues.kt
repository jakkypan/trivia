```java
package com.panda.queue

import android.annotation.TargetApi
import android.os.*
import android.text.TextUtils

/**
 * 依赖handler实现的队列
 */
final class Queues {
    /**
     * 当前队列中的需要传递的数据
     */
    private var bundle: Bundle? = null
    private var opsHandler: Handler? = null
    private var threadName: String = ""
    private var isRunning = false

    /**
     * 在子线程上的队列
     */
    constructor(threadName: String) {
        bundle = Bundle()
        this.threadName = threadName
    }

    /**
     * 在主线程上的队列
     */
    constructor() {
        bundle = Bundle()
    }

    /**
     * 启动队列
     */
    @Synchronized
    fun start() {
        if (isRunning) return
        isRunning = true
        opsHandler = if (!TextUtils.isEmpty(threadName)) {
            val ht = HandlerThread(threadName, Process.THREAD_PRIORITY_DEFAULT)
            ht.start()
            Handler(ht.looper)
        } else {
            Handler(Looper.getMainLooper())
        }
    }

    /**
     * 在队列的尾部加上任务
     */
    fun addOperation(operation: Operation) {
        if (isRunning) {
            opsHandler?.post { operation.run(this, bundle) }
        }
    }

    /**
     * 在队列的头部加上任务，会优先执行
     */
    fun addOperationAtFirst(operation: Operation) {
        if (isRunning) {
            opsHandler?.postAtFrontOfQueue { operation.run(this, bundle) }
        }
    }

    /**
     * 延迟执行
     */
    fun addOperationDelay(operation: Operation, uptimeMillis: Long) {
        if (isRunning) {
            opsHandler?.postDelayed({ operation.run(this, bundle) }, uptimeMillis)
        }
    }

     /**
     * 在所有任务之后才会执行，<b>不能保证延时任务也在之前执行</b>
     */
    @TargetApi(Build.VERSION_CODES.M)
    fun addOperationAtLast(operation: Operation) {
        if (isRunning) {
            opsHandler?.looper?.queue?.addIdleHandler {
                operation.run(this, bundle)
                return@addIdleHandler false
            }
        }
    }

    fun removeAllOperations() {
        if (isRunning) {
            opsHandler?.removeCallbacksAndMessages(null)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        removeAllOperations()
        isRunning = false
        opsHandler = null
        bundle?.clear()
    }

    interface Operation {
        fun run(queue: Queues, bundle: Bundle?)
    }

}
```
