# 线程池的PriorityQueue是否真的能用？

## 问题
我们用线程池进行任务处理，如果要想任务的处理能按优先级的顺序来处理，要怎么做呢？

## 使用PriorityQueue

我们首先想到的是使用PriorityQueue，示例如下：

```java
ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, // core pool size
        1, // maximum pool size
        1000,  // keep alive time
        TimeUnit.SECONDS,  // time unit
        new PriorityBlockingQueue<Runnable>() // worker queue
);
```

按上面的pool执行下面的代码：

```java
public static void main(String[] args) {
    ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, // core pool size
            1, // maximum pool size
            1000,  // keep alive time
            TimeUnit.SECONDS,  // time unit
            new PriorityBlockingQueue<Runnable>() // worker queue
    );

    tpe.submit(new Task("T5", 5));
    tpe.submit(new Task("T4", 4));
    tpe.submit(new Task("T3", 3));
    tpe.submit(new Task("T2", 2));
    tpe.submit(new Task("T1", 1));
}
```

会得到如下的报错：

> Exception in thread "main" java.lang.ClassCastException: class java.util.concurrent.FutureTask cannot be cast to class java.lang.Comparable (java.util.concurrent.FutureTask and java.lang.Comparable are in module java.base of loader 'bootstrap')
	at java.base/java.util.concurrent.PriorityBlockingQueue.siftUpComparable(PriorityBlockingQueue.java:360)
	at java.base/java.util.concurrent.PriorityBlockingQueue.offer(PriorityBlockingQueue.java:486)
	at java.base/java.util.concurrent.ThreadPoolExecutor.execute(ThreadPoolExecutor.java:1347)
	at java.base/java.util.concurrent.AbstractExecutorService.submit(AbstractExecutorService.java:118)
	
#### 为什么报错呢？

看看submit的源码：

```java
public Future<?> submit(Runnable task) {
    if (task == null) throw new NullPointerException();
    RunnableFuture<Void> ftask = newTaskFor(task, null);
    execute(ftask);
    return ftask;
}

protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
    return new FutureTask<T>(runnable, value);
}
```

这里把我们传入的Task，通过`newTaskFor`包装成了`RunnableFuture`，然后把封装类传给`execute()`函数执行。

上面都没有问题，问题在于`execute()`，看看其中的代码片段(ThreadPoolExecutor.java类)：

```java
public void execute(Runnable command) {
	... ...
      if (isRunning(c) && workQueue.offer(command)) {
            int recheck = ctl.get();
            if (! isRunning(recheck) && remove(command))
                reject(command);
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
      }
      ... ...
}
```

问题就在其中的`workQueue.offer()`，他最终会调用到`PriorityBlockingQueue.java`的offer函数，最后调用到其中的`siftUpComparable()`函数：

```java
private static <T> void siftUpComparable(int k, T x, Object[] array) {
    Comparable<? super T> key = (Comparable<? super T>) x;
    while (k > 0) {
        int parent = (k - 1) >>> 1;
        Object e = array[parent];
        if (key.compareTo((T) e) >= 0)   !!! 报错的地方
            break;
        array[k] = e;
        k = parent;
    }
    array[k] = key;
}
```

这里报错的原因在于需要的e是`Comparable`类型的，但是e不是，他是`RunnableFuture`，定义为：
> public interface RunnableFuture<V> extends Runnable, Future<V>

所以这里就执行crash了。


## 解决方案

为了解决上面的crash问题，我们需要做件定制化的事情：

需要保证从`newTaskFor()`返回的`RunnableFuture`对象是实现了`Comparable`接口的，这样才能保证task被加入到`PriorityQueue`中。

具体代码如下：

> 1、Task.java：带优先级的任务定义

```java
class Task implements Runnable {
    private final String name;
    private final int priority;

    Task(String name, int priority) {
        this.name = name;
        this.priority = priority;
    }

    @Override
    public void run() {
        try {
            Log.e("111", "[{}] triggered successfully " + this.name + "->" + this.priority);
            Thread.sleep(200);
            Log.e("111", "[{}] completed successfully " + this.name + "->" + this.priority);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    public int getPriority() {
        return priority;
    }
}
```

> 2、CustomFutureTask.java：自定义的FutureTask，满足Comparable

```java
class CustomFutureTask<T> extends FutureTask<T> implements Comparable<CustomFutureTask<T>> {

    private final Task task;

    public CustomFutureTask(Runnable task) {
        super(task, null);
        this.task = (Task) task;
    }

    @Override
    public int compareTo(CustomFutureTask that) {
        return Integer.compare(this.task.getPriority(), that.task.getPriority());
    }
}
```

> 3、CustomSingleThreadPoolExecutor.java：自定义的ThreadPoolExecutor，重写newTaskFor

```java
class CustomSingleThreadPoolExecutor extends ThreadPoolExecutor {
    public CustomSingleThreadPoolExecutor(BlockingQueue<Runnable> workQueue) {
        super(1, 1, 1000, TimeUnit.SECONDS, workQueue);
    }

    @Override
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new CustomFutureTask<>(runnable);
    }
}
```

> 4、Runner.java：测试代码

```java
public class Runner {
    public static void main(String[] args) {
        ThreadPoolExecutor tpe = new CustomSingleThreadPoolExecutor(new PriorityBlockingQueue<Runnable>(3));
        tpe.submit(new Task("T5", 5));
        tpe.submit(new Task("T4", 4));
        tpe.submit(new Task("T3", 0));
        tpe.submit(new Task("T2", 1));
        tpe.submit(new Task("T100", 100));
        tpe.submit(new Task("T1", 2));
        tpe.submit(new Task("T0", 3));
        tpe.submit(new Task("T10", 10));
        tpe.submit(new Task("T8", 8));
        tpe.submit(new Task("T12", 12));
        tpe.submit(new Task("T6", 6));
        tpe.submit(new Task("T-1", -1));

        tpe.shutdown();
    }
}
```

执行结果：

```
[{}] triggered successfully T4->4
[{}] triggered successfully T5->5
[{}] completed successfully T5->5
[{}] completed successfully T4->4
[{}] triggered successfully T-1->-1
[{}] triggered successfully T3->0
[{}] completed successfully T-1->-1
[{}] completed successfully T3->0
[{}] triggered successfully T2->1
[{}] triggered successfully T1->2
[{}] completed successfully T2->1
[{}] completed successfully T1->2
[{}] triggered successfully T0->3
[{}] triggered successfully T6->6
[{}] completed successfully T6->6
[{}] completed successfully T0->3
[{}] triggered successfully T8->8
[{}] triggered successfully T10->10
[{}] completed successfully T8->8
[{}] completed successfully T10->10
[{}] triggered successfully T12->12
[{}] triggered successfully T100->100
[{}] completed successfully T100->100
[{}] completed successfully T12->12
```

## 原文

[Prioritize tasks in a ThreadPoolExecutor](https://jvmaware.com/priority-queue-and-threadpool/)