# Priv-Bridge-1：Shizuku UserService 由 server 经 app_process 反射实例化（含 Context 构造），
# 且 AIDL Stub 方法经 Binder 事务码调用，均不可被 R8 裁剪/混淆。
-keep class com.erl.blindcast.core.priv.PrivilegedUserService { *; }
-keep class com.erl.blindcast.core.priv.IPrivilegedOps* { *; }
