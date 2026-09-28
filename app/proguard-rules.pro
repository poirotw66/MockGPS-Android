# Keep project-specific R8 rules here when release shrinking is enabled.
-keep class com.android.billingclient.** { *; }
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.android.billingclient.**

