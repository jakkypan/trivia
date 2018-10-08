# Android P对API限制的原理与绕过

从android P开始，google严格控制了反射调用非开放API，这里到底发生了生么呢？我们是否能绕过google的限制呢？本文将基于android P的源码来看看google是怎么实现这个功能的，并在了解google的限制原理的情况下看看如何实现绕过。

## 限制的源码分析

### 1、 Class.java

```java
/**
 * Returns the method if it is defined by this class; {@code null} otherwise. This may return a
 * non-public member.
 *
 * @param name the method name
 * @param args the method's parameter types
 */
@FastNative
private native Method getDeclaredMethodInternal(String name, Class<?>[] args);
```

所有对外暴露的反射方法的API最后调用的都是该native方法。


### 2、framework/runtime/native/java\_lang\_Class.cc

```c
static jobject Class_getDeclaredMethodInternal(JNIEnv* env, jobject javaThis,
                                               jstring name, jobjectArray args) {
  ScopedFastNativeObjectAccess soa(env);
  StackHandleScope<1> hs(soa.Self());
  DCHECK_EQ(Runtime::Current()->GetClassLinker()->GetImagePointerSize(), kRuntimePointerSize);
  DCHECK(!Runtime::Current()->IsActiveTransaction());
  Handle<mirror::Method> result = hs.NewHandle(
      mirror::Class::GetDeclaredMethodInternal<kRuntimePointerSize, false>(
          soa.Self(),
          DecodeClass(soa, javaThis),
          soa.Decode<mirror::String>(name),
          soa.Decode<mirror::ObjectArray<mirror::Class>>(args)));
  if (result == nullptr || ShouldBlockAccessToMember(result->GetArtMethod(), soa.Self())) {
    return nullptr;
  }
  return soa.AddLocalReference<jobject>(result.Get());
}
```

**这里的改变是增加了`ShouldBlockAccessToMember()`方法，就是在这个方法里完成了对API反射的限制。**

可以同步看下8.1上的源码，来佐证上面的结论：

```c
static jobject Class_getDeclaredConstructorInternal(
    JNIEnv* env, jobject javaThis, jobjectArray args) {
  ScopedFastNativeObjectAccess soa(env);
  DCHECK_EQ(Runtime::Current()->GetClassLinker()->GetImagePointerSize(), kRuntimePointerSize);
  DCHECK(!Runtime::Current()->IsActiveTransaction());
  ObjPtr<mirror::Constructor> result =
      mirror::Class::GetDeclaredConstructorInternal<kRuntimePointerSize, false>(
      soa.Self(),
      DecodeClass(soa, javaThis),
      soa.Decode<mirror::ObjectArray<mirror::Class>>(args));
  return soa.AddLocalReference<jobject>(result);
}
```

`ShouldBlockAccessToMember()`的具体实现：

```java
// Returns true if the first non-ClassClass caller up the stack should not be
// allowed access to `member`.
template<typename T>
ALWAYS_INLINE static bool ShouldBlockAccessToMember(T* member, Thread* self)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  hiddenapi::Action action = hiddenapi::GetMemberAction(
      member, self, IsCallerTrusted, hiddenapi::kReflection);
  if (action != hiddenapi::kAllow) {
    hiddenapi::NotifyHiddenApiListener(member);
  }
  return action == hiddenapi::kDeny;
}
```

### 3、framework/runtime/hidden_api.h

`GetMemberAction()`方法：

```c
template<typename T> inline Action GetMemberAction(T* member, Thread* self, std::function<bool(Thread*)> fn_caller_is_trusted, AccessMethod access_method)
    REQUIRES_SHARED(Locks::mutator_lock_) {
  DCHECK(member != nullptr);
  HiddenApiAccessFlags::ApiList api_list = member->GetHiddenApiAccessFlags();
  Action action = GetActionFromAccessFlags(member->GetHiddenApiAccessFlags());
  if (action == kAllow) {
    return action;
  }
  
  if (fn_caller_is_trusted(self)) {
    return kAllow;
  }
  
  return detail::GetMemberActionImpl(member, api_list, action, access_method);
}
```

注意这里的`T* member`就是我们需要反射的方法。

### 4、framework/runtime/art_method-inl.h

`GetHiddenApiAccessFlags()`方法，这个是用来获取到API的access\_flags\_。

这里的级别定义为：

```c
enum ApiList {
	kWhitelist = 0,
	kLightGreylist,
	kDarkGreylist,
	kBlacklist,
};
```

这些是access\_flags\_是对原来的private、protected、static等的扩展，概念上是一个级别的。这里面具体的代码逻辑可以参考代码：`framework/compiler/optimizing/intrinsics.cc`和`framework/runtime/art_method.h`。

### 5、framework/runtime/hidden_api.h

`GetActionFromAccessFlags()`是根据上面`GetHiddenApiAccessFlags()`返回的访问级别来获取到对应的Action。

Action也是一个枚举值：

```c
enum Action {
   kAllow,
   kAllowButWarn,
   kAllowButWarnAndToast,
   kDeny
};
```

在进入`GetActionFromAccessFlags()`的代码之前，需要先了解一个枚举类EnforcementPolicy：

```c
enum class EnforcementPolicy {
  kNoChecks             = 0,
  kJustWarn             = 1,  // keep checks enabled, but allow everything (enables logging)
  kDarkGreyAndBlackList = 2,  // ban dark grey & blacklist
  kBlacklistOnly        = 3,  // ban blacklist violations only
  kMax = kBlacklistOnly,
};
```

action的获取逻辑是：

```c
inline Action GetActionFromAccessFlags(HiddenApiAccessFlags::ApiList api_list) {
  if (api_list == HiddenApiAccessFlags::kWhitelist) {
    return kAllow;
  }
  EnforcementPolicy policy = Runtime::Current()->GetHiddenApiEnforcementPolicy();
  if (policy == EnforcementPolicy::kNoChecks) {
    // Exit early. Nothing to enforce.
    return kAllow;
  }
  // if policy is "just warn", always warn. We returned above for whitelist APIs.
  if (policy == EnforcementPolicy::kJustWarn) {
    return kAllowButWarn;
  }
  DCHECK(policy >= EnforcementPolicy::kDarkGreyAndBlackList);
  // The logic below relies on equality of values in the enums EnforcementPolicy and
  // HiddenApiAccessFlags::ApiList, and their ordering. Assertions are in hidden_api.cc.
  if (static_cast<int>(policy) > static_cast<int>(api_list)) {
    return api_list == HiddenApiAccessFlags::kDarkGreylist
        ? kAllowButWarnAndToast
        : kAllowButWarn;
  } else {
    return kDeny;
  }
}
```

1. 如果是ApiList::kWhitelist，则直接返回Action::kAllow
2. 获取Runtime的EnforcementPolicy值
3. 如果EnforcementPolicy的值为kJustWarn，则返回Action::kAllowButWarn
4. 比较EnforcementPolicy的值和ApiList的值
 * 4.1 如果EnforcementPolicy的值大于ApiList的值，则返回Action::kAllowButWarnAndToast或Action::kAllowButWarn
 * 4.2 反之，则返回Action::kDeny

这里的`GetHiddenApiEnforcementPolicy()`方法是在`framework/runtime/runtime.h`里定义的：

```c
hiddenapi::EnforcementPolicy GetHiddenApiEnforcementPolicy() const {
    return hidden_api_policy_;
}
```

### 6、fn\_caller\_is\_trusted()

函数指针，用来判断调用者是不是安卓系统自身，如果是的话，则直接返回Action::kAllow，说明我不对自己做任何的拦截。

**判断是不是系统自己来调用的是通过判断该Class的ClassLoader是不是BootClassLoader加载的即可。**

这个具体的方法是在`framework/runtime/native/java_lang_Class.cc`里的`IsCallerTrusted(Thread* self)`方法。

### 7、framework/runtime/hidden_api.cc

`GetMemberActionImpl()`这个方法是用来干什么的呢？**这里是个“豁免”处理**。

```c
template<typename T>
Action GetMemberActionImpl(T* member,
                           HiddenApiAccessFlags::ApiList api_list,
                           Action action,
                           AccessMethod access_method) {
     ... ...
     const bool shouldWarn = kLogAllAccesses || runtime->IsJavaDebuggable();
     if (shouldWarn || action == kDeny) {
        if (member_signature.IsExempted(runtime->GetHiddenApiExemptions())) {
          action = kAllow;
          MaybeWhitelistMember(runtime, member);
          return kAllow;
        }
      
        if (access_method != kNone) {
           member_signature.WarnAboutAccess(access_method, api_list);
        }
     }
     ... ...
}
```

`GetHiddenApiExemptions()`的方法在`framework/runtime/runtime.h`里定义的：

```c
const std::vector<std::string>& GetHiddenApiExemptions() {
    return hidden_api_exemptions_;
}
```

## 绕过限制

绕过的方法就是如何突破上面源码分析的几个点，总结自：

* [突破Android P非公开API限制](http://www.infoq.com/cn/news/2018/04/Android-P-API)
* [一种绕过Android P对非SDK接口限制的简单方法](http://weishu.me/2018/06/07/free-reflection-above-android-p/)：

### 1、kNoChecks

在`GetActionFromAccessFlags()`里，会通过调用`GetHiddenApiEnforcementPolicy()`来获取限制策略，如果让`GetHiddenApiEnforcementPolicy()`直接返回kNoChecks，那么`GetActionFromAccessFlags()`就会返回Action::kAllow，那么就可以访问了。

### 2、BootStrapClassLoader

上面也提到，`fn_caller_is_trusted()`函数支持通过判断回溯调用栈，通过判断调用者Class是不是BootStrapClassLoader来判断是不是系统，如果是的话，则直接返回Action:: kAllow。

这里我们可以欺骗系统，让系统错误地将用户代码调用识别为系统代码调用。

### 3、豁免

最后我们看到了`GetMemberActionImpl()`方法的“豁免”处理，如果我们去修改`GetHiddenApiExemptions()`的返回值是在豁免的规则里，那么就能返回Action::kAllow。

> 总结

**上面的3点都是可以做绕过限制的hook点，只要实现其中的任何一个就能实现染过Android P的限制。**网上已经开源了一个sdk，可以参考下[https://github.com/tiann/FreeReflection](https://github.com/tiann/FreeReflection)。