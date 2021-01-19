# Android静默安装

## 1、通过shell命令安装

### 1.1. Android7.0以下

Android7.0以下，用下面的shell命令通过adb调用或者代码中调用，都能正常运行，需要注意的是apk的路径必须是绝对路径：

```
pm install -r -d /sdcard/Download/demo.apk
```

### 1.2. Android7.0到Android9.0以下

应用安装的限制有所加强，通过adb shell安装命令如下，跟Android7.0以下一样

```
pm install -r -d /sdcard/Download/demo.apk
```

但是在代码中调用就不一样，需要用-i参数指定一个系统进程来安装，如下com.android.settings是一个系统应用

```
pm install -r -d -i com.android.settings /sdcard/Download/demo.apk
```

### 1.3. Android9.0及以上
经验证，在Android9.0及以上的系统，无法在代码中调用shell命令安装app；

通过阅读Android源码发现，Android9.0在应用安装流程中，首先进行了进程的识别，如果当前是shell进程，直接安装失败。用下面介绍的方法，可以绕过此限制。

## 2. 通过Android API安装

### 2.1. Android9.0以下

调用PackageManager的installPackage可实现静默安装，但installPackage是隐藏的，带系统签名的应用可以通过反射的方式调用

```
public static boolean silentInstall(PackageManager packageManager, String apkPath) {
    Class<?> pmClz = packageManager.getClass();
    try {
        if (Build.VERSION.SDK_INT >= 21) {
            Class<?> aClass = Class.forName("android.app.PackageInstallObserver");
            Constructor<?> constructor = aClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object installObserver = constructor.newInstance();
            Method method = pmClz.getDeclaredMethod("installPackage", Uri.class, aClass, int.class, String.class);
            method.setAccessible(true);
            method.invoke(packageManager, Uri.fromFile(new File(apkPath)), installObserver, 2, null);
        } else {
            Method method = pmClz.getDeclaredMethod("installPackage", Uri.class, Class.forName("android.content.pm.IPackageInstallObserver"), int.class, String.class);
            method.setAccessible(true);
            method.invoke(packageManager, Uri.fromFile(new File(apkPath)), null, 2, null);
        }
        return true;
    } catch (Exception e) {
        e.printStackTrace();
    }
    return false;
}
```

### 2.2. Android9.0及以上

Android9.0中PackageManager的installPackage已经被废弃了，可用如下新的接口

```
public static boolean install(Context context, String apkPath) {
    PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
    SessionParams params = new SessionParams(SessionParams.MODE_FULL_INSTALL);
    String pkgName = getApkPackageName(context, apkPath);
    if (pkgName == null) {
        return false;
    }
    params.setAppPackageName(pkgName);
    try {
        Method allowDowngrade = SessionParams.class.getMethod("setAllowDowngrade", boolean.class);
        allowDowngrade.setAccessible(true);
        allowDowngrade.invoke(params, true);
    } catch (Exception e) {
        e.printStackTrace();
    }
    OutputStream os = null;
    InputStream is = null;
    try {
        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        os = session.openWrite(pkgName, 0, -1);
        is = new FileInputStream(apkPath);
        byte[] buffer = new byte[1024];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
        }
        session.fsync(os);
        os.close();
        os = null;
        is.close();
        is = null;
        session.commit(PendingIntent.getBroadcast(context, sessionId,
                new Intent(Intent.ACTION_MAIN), 0).getIntentSender());
    } catch (Exception e) {
        Logger.e("" + e.getMessage());
        return false;
    } finally {
        if (os != null) {
            try {
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    return true;
}
/**
 * 获取apk的包名
 */
public static String getApkPackageName(Context context, String apkPath) {
    PackageManager pm = context.getPackageManager();
    PackageInfo info = pm.getPackageArchiveInfo(apkPath, 0);
    if (info != null) {
        return info.packageName;
    } else {
        return null;
    }
}
```