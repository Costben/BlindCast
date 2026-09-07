plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

val androidMinSdkVersion by extra(31)
val androidTargetSdkVersion by extra(37)
val androidCompileSdkVersion by extra(37)
val androidCompileSdkVersionMinor by extra(0)
val androidBuildToolsVersion by extra("37.0.0")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)
// 版本纪律：X.Y.Z.W 四段 —— W=每次推送 216 的小迭代(+1)；Z=功能批次；Y=大里程碑；X=重写级。
// 0.0.1 算大版本，日常推送只动 W。versionCode 每次必 +1（安装排序与应用内更新检查只认它）。
val managerVersionCode by extra(102)
val managerVersionName by extra("0.1.0.2")
