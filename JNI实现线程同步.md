# JNI实现线程同步

## 核心API

### MonitorEnter

```
jint MonitorEnter(JNIEnv *env, jobject obj);
```

当前线程输入被指定对象的监控程序，除非另一个线程已经锁住该对象，此时当前线程将暂停直至另一个线程释放对象的监控程序。如果当前线程已经锁住对象的监控程序，则对于该函数对该对象的每一次调用计数器都将增1。函数成功则返回0，失败则返回一个负值。

### MonitorExit

```
jint MonitorExit(JNIEnv *env, jobject obj);
```

MonitorExit函数将对象的监控程序计数器减1。如果计数器值为0则释放当前线程在该对象上的锁。函数执行成功则返回0，失败返回一个负值。

## 和synchronized的联系

synchronized的底层实现其实就是MonitorEnter和MonitorExit。可以通过调用JNI函数来达到与上述JAVA代码中等效的同步目的。

java的同步实现：

```java
synchronized (obj) {
     ...  // synchronized block
 }
```

对应的JNI实现：

```c
if ((*env)->MonitorEnter(env, obj) != JNI_OK) {
     ... /* error handling */
 }

 ...  /* synchronized block */

 if ((*env)->MonitorExit(env, obj) != JNI_OK) {
     ... /* error handling */
 };
```

> 调用MonitorEnter而不调用MonitorExit的话，很可能会引起死锁。为了避免死锁，使用 MonitorEnter方法来进入监视区，必须使用MonitorExit 来退出，或调用 DetachCurrentThread 来明确释放JNI监视区。

## 例子

上层代码：

```java
package ndk.example.com.ndkexample;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("native-lib");
    }

    private int modify = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView tv = (TextView) findViewById(R.id.sample_text);
        tv.setText("启动10条线程...");
        for (int i = 0; i < 10; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    modify();
                    nativeModify();
                }
            }).start();
        }
    }

    private void modify() {
        synchronized (this) {
            modify++;
            Log.d("Java", "modify=" + modify);
        }
    }

    public native void nativeModify();

}
```

jni代码：

```c
#include <jni.h>
#include <android/log.h>

#define TAG "Native"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__))

extern "C"
JNIEXPORT void JNICALL
Java_ndk_example_com_ndkexample_MainActivity_nativeModify(JNIEnv *env, jobject instance) {
    jclass cls = env->GetObjectClass(instance);
    jfieldID fieldID = env->GetFieldID(cls, "modify", "I");

    if (env->MonitorEnter(instance) != JNI_OK) {
        LOGE("%s: MonitorEnter() failed", __FUNCTION__);
    }

    /* synchronized block */
    int val = env->GetIntField(instance, fieldID);
    val++;
    LOGI("modify=%d", val);
    env->SetIntField(instance, fieldID, val);

    if (env->ExceptionOccurred()) {
        LOGE("ExceptionOccurred()...");
        if (env->MonitorExit(instance) != JNI_OK) {
            LOGE("%s: MonitorExit() failed", __FUNCTION__);
        };
    }

    if (env->MonitorExit(instance) != JNI_OK) {
        LOGE("%s: MonitorExit() failed", __FUNCTION__);
    };

}
```

结果：

```
01-30 10:56:01.110 14313-14336/ndk.example.com.ndkexample D/Java: modify=1
01-30 10:56:01.110 14313-14334/ndk.example.com.ndkexample D/Java: modify=2
01-30 10:56:01.110 14313-14334/ndk.example.com.ndkexample I/Native: modify=3
01-30 10:56:01.110 14313-14336/ndk.example.com.ndkexample I/Native: modify=4
01-30 10:56:01.110 14313-14335/ndk.example.com.ndkexample D/Java: modify=5
01-30 10:56:01.111 14313-14335/ndk.example.com.ndkexample I/Native: modify=6
01-30 10:56:01.111 14313-14337/ndk.example.com.ndkexample D/Java: modify=7
01-30 10:56:01.111 14313-14337/ndk.example.com.ndkexample I/Native: modify=8
01-30 10:56:01.111 14313-14340/ndk.example.com.ndkexample D/Java: modify=9
01-30 10:56:01.111 14313-14340/ndk.example.com.ndkexample I/Native: modify=10
01-30 10:56:01.111 14313-14342/ndk.example.com.ndkexample D/Java: modify=11
01-30 10:56:01.111 14313-14342/ndk.example.com.ndkexample I/Native: modify=12
01-30 10:56:01.111 14313-14338/ndk.example.com.ndkexample D/Java: modify=13
01-30 10:56:01.113 14313-14339/ndk.example.com.ndkexample D/Java: modify=14
01-30 10:56:01.114 14313-14343/ndk.example.com.ndkexample D/Java: modify=15
01-30 10:56:01.114 14313-14343/ndk.example.com.ndkexample I/Native: modify=16
01-30 10:56:01.114 14313-14338/ndk.example.com.ndkexample I/Native: modify=17
01-30 10:56:01.114 14313-14341/ndk.example.com.ndkexample D/Java: modify=18
01-30 10:56:01.114 14313-14339/ndk.example.com.ndkexample I/Native: modify=19
01-30 10:56:01.114 14313-14341/ndk.example.com.ndkexample I/Native: modify=20
```

## 关于Android的线程

### 线程创建

Android Native中支持的线程标准是 POSIX 线程。POSIX 线程也被简称为Pthreads，是一个线程的POSIX 标准，它为创建和处理线程定义了一个通用的API。

POSIX Thread 的Android实现是Bionic标准库的一部分，在编译的时候不需要链接任何其他的库，只需要包含一个头文件：

```c
#include <pthread.h>
```

创建方法：

```c
int pthread_create(pthread_t* __pthread_ptr, pthread_attr_t const* __attr, void* (*__start_routine)(void*), void* arg)
```

* `__pthread_ptr`：指向 pthread_t 类型变量的指针，用它代表返回线程的句柄
* `__attr`：指向`pthread_attr_t`结构的指针形式存在的新线程属性，可以通过该结构来指定新线程的一些属性，比如栈大小、调度优先级等，具体看`pthread_attr_t`结构的内容。如果没有特殊要求，可使用默认值，把该变量取值为 NULL
* `第三个参数`：指向启动函数的函数指针
* `arg`：线程启动程序的参数，也就是函数的参数，如果不需要传递参数，它可以为 NULL

例子：

```c
void sayHello(void *){
    LOGE("say %s","hello");
}

JNIEXPORT jint JNICALL Java_com_david_JNIController_sayhello
        (JNIEnv *jniEnv, jobject instance) {
    pthread_t handles; // 线程句柄
    int ret = pthread_create(&handles, NULL, sayHello, NULL);
    if (ret != 0) {
        LOGE("create thread failed");
    } else {
        LOGD("create thread success");
    }
}
```

### 等待线程结果

新线程运行后，该方法也就立即返回退出，执行完了。我们也可以通过另一个函数可以在等待线程执行完毕后，拿到线程执行完的结果之后再退出。

```
int pthread_join(pthread_t pthread, void** ret_value);
```

* `pthread`：代表创建线程的句柄
* `ret_value`：代表线程运行函数返回的结果

### 与java虚拟机绑定

创建了线程后，只能做一些简单的Native操作，如果想要对Java层做一些操作就不行了，因为它没有Java虚拟机环境，这个时候为了和Java空间进行交互，就要把POSIX 线程附着在Java虚拟机上，然后就可以获得当前线程的 JNIEnv 指针了。

通过`AttachCurrentThread`方法可以将当前线程附着到 Java 虚拟机上，并且可以获得 JNIEnv 指针。而`AttachCurrentThread`方法是由 JavaVM 指针调用的，可以在`JNI_OnLoad`函数中将JavaVM保存为全局变量。

例子：

```c
static JavaVM *jVm = NULL;
JNIEXPORT int JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    jVm = vm;
    return JNI_VERSION_1_6;
}

void sayHello(void *){
    LOGE("say %s","hello");
     JNIEnv *env = NULL;
    // 将当前线程添加到 Java 虚拟机上
    if (jVm->AttachCurrentThread(&env, NULL) == 0) {
        ......
        env->CallVoidMethod(Obj, javaSayHello);  //javaSayHello为java层方法
        // 从 Java 虚拟机上分离当前线程
        jVm->DetachCurrentThread();  
    }
    return NULL;
}
```
