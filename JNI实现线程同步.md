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




