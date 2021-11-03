# 安卓限制API的豁免方法

## 系统如何限制的？

参考：[Android R上的隐藏API限制学习笔记](https://blog.canyie.top/2020/06/10/hiddenapi-restriction-policy-on-android-r/)

## 1、adb命令

google给开发人员提供了去掉api限制的方法，方便开发者进行功能测试。并且这些命令都不需要root权限。

可以用如下命令解除限制：

```
adb shell settings put global hidden_api_policy_pre_p_apps  1
adb shell settings put global hidden_api_policy_p_apps 1
```

重置的命令如下：

```
adb shell settings delete global hidden_api_policy_pre_p_apps
adb shell settings delete global hidden_api_policy_p_apps
```

上面的int值可以是如下的几个值：

* 0: Disable all detection of non-SDK interfaces. Using this setting disables all log messages for non-SDK interface usage and prevents you from testing your app using the StrictMode API. This setting is not recommended.
* 1: Enable access to all non-SDK interfaces, but print log messages with warnings for any non-SDK interface usage. Using this setting also allows you to test your app using the StrictMode API.
* 2: Disallow usage of non-SDK interfaces that belong to either the blacklist or the greylist and are restricted for your target API level.
* 3: Disallow usage of non-SDK interfaces that belong to the blacklist, but allow usage of interfaces that belong to the greylist and are restricted for your target API level.

> 需要注意的是，在代码里调用shell命令并不能生效。

## 2、Settings API

这个不是给普通app使用的，只有系统App才能使用。

使用是需要有`WRITE_SECURE_SETTINGS`权限。然后在代码里调用：

```
Settings.Global.putInt(ContentResolver, String, Int)
```

## 3、RestrictionBypass库

git地址：[RestrictionBypass](https://github.com/ChickenHook/RestrictionBypass)

原理介绍：[ANDROID API RESTRICTION BYPASS FOR ALL ANDROID VERSIONS](https://androidreverse.wordpress.com/2020/05/02/android-api-restriction-bypass-for-all-android-versions/)

简单介绍就是让系统判断反射调用是系统的而不是APP的，因为安卓源码是通过回溯调用栈，通过调用者的Class来判断是否是系统代码的调用（所有系统的代码都通过BootClassLoader加载，判断ClassLoader即可）。RestrictionBypass库的做法是通过在jni层新建个线程，在这个线程里去反射，去除掉了java调用的信息，从而让安卓系统以为这个是系统调用。

## 4、FreeReflection库

git地址：[FreeReflection](https://github.com/tiann/FreeReflection)

原理：借助系统的类去反射

1. 我们通过反射 API 拿到 getDeclaredMethod 方法。getDeclaredMethod 是 public 的，不存在问题；这个通过反射拿到的方法我们称之为元反射方法。
2. 我们通过刚刚反射拿到元反射方法去反射调用 getDeclardMethod。这里我们就实现了以系统身份去反射的目的——反射相关的 API 都是系统类，因此我们的元反射方法也是被系统类加载的方法；所以我们的元反射方法调用的 getDeclardMethod 会被认为是系统调用的，可以反射任意的方法。

例子：

```
Method metaGetDeclaredMethod =
        Class.class.getDeclaredMethod("getDeclardMethod"); // 公开API，无问题
Method hiddenMethod = metaGetDeclaredMethod.invoke(hiddenClass,
        "hiddenMethod", "hiddenMethod参数列表"); // 系统类通过反射使用隐藏 API，检查直接通过。
hiddenMethod.invoke // 正确找到 Method 直接反射调用
```

按照这个思路，我们反射`setHiddenApiExemptions`方法，正好这个放在在`dalvik/system/VMRuntime.java`：

```
/**
     * Sets the list of exemptions from hidden API access enforcement.
     *
     * @param signaturePrefixes
     *         A list of signature prefixes. Each item in the list is a prefix match on the type
     *         signature of a blacklisted API. All matching APIs are treated as if they were on
     *         the whitelist: access permitted, and no logging..
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public native void setHiddenApiExemptions(String[] signaturePrefixes);
```

> FreeReflection的一个优化

apk正常的类加载的loader是PathClassLpader，这在高版本安卓系统上可能会失败。因为系统的类的loader都是BootClassLoader，所以将反射的代码的loader改成BootClassLoader，那么成功率会大幅提高。

具体做法：

1. 将源代码打包成dex文件
2. 将dex内容读出来并通过Base64转码，hardcode写到代码里
3. 将上面的代码decode出来，写入到文件中
4. 通过DexFile加载上面的文件
5. 通过loadClass加载class，并调用对应的方法

## 5、AndroidHiddenApiBypass库

git地址：[AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)

原理：[一个通用的纯 Java 安卓隐藏 API 限制绕过方案](https://lovesykun.cn/archives/android-hidden-api-bypass.html)

主要是通过UnSafe类去修改内存。**FreeReflection库在c++层也实现了这样的功能。**

## 6、android-restriction-bypass库

git地址：[android-restriction-bypass](https://github.com/quarkslab/android-restriction-bypass)

