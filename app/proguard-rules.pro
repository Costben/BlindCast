# Priv-Bridge-1：Shizuku UserService 由 server 经 app_process 反射实例化（含 Context 构造），
# 且 AIDL Stub 方法经 Binder 事务码调用，均不可被 R8 裁剪/混淆。
-keep class com.erl.blindcast.core.priv.PrivilegedUserService { *; }
-keep class com.erl.blindcast.core.priv.IPrivilegedOps* { *; }
# Root-Backend-1：Root 真身单次执行器由 su 下 app_process 按类名拉起 main(String[])，
# 不可被 R8 裁剪/混淆/内联入口。
-keep class com.erl.blindcast.core.priv.RootMain { *; }
-keepclassmembers class com.erl.blindcast.core.priv.RootMain {
    public static void main(java.lang.String[]);
}
