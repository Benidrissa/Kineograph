# LiteRT-LM's native code finds its Kotlin classes, callbacks and exceptions by name, and the library ships no rules of its own, so
# shrinking must leave the whole package as it is.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
