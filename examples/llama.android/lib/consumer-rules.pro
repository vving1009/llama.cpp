-keep class com.arm.aichat.* { *; }
-keep class com.arm.aichat.gguf.* { *; }
-keep class com.arm.aichat.mcp.* { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class kotlin.Metadata { *; }
