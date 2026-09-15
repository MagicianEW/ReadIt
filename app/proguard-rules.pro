# ReadIt / 阅即
-keepattributes Signature
-keep class com.readit.** { *; }

# juniversalchardet
-keep class org.mozilla.universalchardet.** { *; }

# PdfBox-Android
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn org.apache.fontbox.**
-dontwarn com.tom_roush.pdfbox.**

# PdfiumAndroid —— 渲染入口全部是 JNI native 方法（libjniPdfium.so 通过
# 类名+方法名反查），一旦将来开启 R8 缩小/混淆，类名或方法签名被改写
# 会直接导致 UnsatisfiedLinkError、PDF 页白屏。此处预留 keep 规则。
-keep class com.shockwave.pdfium.** { *; }
-dontwarn com.shockwave.pdfium.**

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
