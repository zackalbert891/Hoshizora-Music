# Gson 反射解析的模型类，混淆时保留字段名（否则 JSON 解析会失败）。
-keepclassmembers class com.puremusic.source.qq.** { <fields>; }
-keepclassmembers class com.puremusic.ui.login.QqLoginManager$** { <fields>; }
-keep class com.puremusic.model.** { *; }
