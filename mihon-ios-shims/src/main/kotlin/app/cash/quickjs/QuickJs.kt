package app.cash.quickjs

import kotlinx.serialization.json.*
import org.skepsun.kototoro.ios.shims.IosNative
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** QuickJs's extension-facing ABI, executed in an isolated Apple JavaScriptCore context. */
class QuickJs : Closeable {
    private var context = IosNative.createJavaScript(this)
    private val bindings = mutableMapOf<String, Pair<Any, Map<String, Method>>>()

    @Synchronized
    fun evaluate(script: String, fileName: String): Any? {
        check(context != 0L) { "QuickJs is closed" }
        return decode(Json.parseToJsonElement(IosNative.evaluateJavaScript(context, script, fileName)))
    }
    fun evaluate(script: String): Any? = evaluate(script, "?")

    @Synchronized
    fun <T> set(name: String, type: Class<T>, instance: T) {
        require(type.isInterface && type.isInstance(instance)) { "JavaScript bindings require an interface instance" }
        val methods = type.methods.associateBy { it.name }
        require(methods.size == type.methods.size) { "Overloaded JavaScript methods are unsupported" }
        methods.values.forEach { it.isAccessible = true }
        bindings[name] = requireNotNull(instance) as Any to methods
        val key = JsonPrimitive(name).toString()
        val members = methods.keys.joinToString(",") { method ->
            val quoted = JsonPrimitive(method).toString()
            "$quoted:function(){return JSON.parse(__kototoroInvoke($key,$quoted,JSON.stringify(Array.from(arguments))));}"
        }
        evaluate("globalThis[$key]={$members}")
    }

    @Synchronized
    fun <T> get(name: String, type: Class<T>): T {
        require(type.isInterface)
        val key = JsonPrimitive(name).toString()
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            if (method.declaringClass == Any::class.java) when (method.name) {
                "toString" -> "JavaScript binding $name"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error(method.name)
            } else convert(evaluate("globalThis[$key][${JsonPrimitive(method.name)}].apply(globalThis[$key],${encode(args ?: emptyArray<Any>())})"), method.returnType)
        })
    }

    /** JNI callback from this context's __kototoroInvoke function, on the evaluating thread. */
    @Synchronized
    fun invokeBridge(name: String, method: String, arguments: String): String {
        val (instance, methods) = bindings.getValue(name)
        val target = methods.getValue(method)
        val args = Json.parseToJsonElement(arguments).jsonArray
        require(args.size == target.parameterCount)
        val converted = args.mapIndexed { index, arg -> convert(decode(arg), target.parameterTypes[index]) }.toTypedArray()
        return try { encode(target.invoke(instance, *converted)).toString() }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    fun compile(script: String, fileName: String): ByteArray =
        throw UnsupportedOperationException("JavaScriptCore does not export QuickJS bytecode ($fileName)")
    fun execute(bytecode: ByteArray): Any? =
        throw UnsupportedOperationException("JavaScriptCore cannot execute QuickJS bytecode (${bytecode.size} bytes)")

    @Synchronized
    override fun close() {
        if (context == 0L) return
        IosNative.closeJavaScript(context)
        context = 0
        bindings.clear()
    }

    companion object {
        @JvmStatic fun create(): QuickJs = QuickJs()
        private fun decode(value: JsonElement): Any? = when (value) {
            JsonNull -> null
            is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.doubleOrNull
            is JsonArray -> value.map(::decode).toTypedArray()
            is JsonObject -> value.mapValues { decode(it.value) }
        }
        private fun encode(value: Any?): JsonElement = when (value) {
            null, Unit -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Array<*> -> JsonArray(value.map(::encode))
            is IntArray -> JsonArray(value.map(::encode))
            is LongArray -> JsonArray(value.map(::encode))
            is DoubleArray -> JsonArray(value.map(::encode))
            is Iterable<*> -> JsonArray(value.map(::encode))
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to encode(it.value) })
            else -> throw IllegalArgumentException("Unsupported JavaScript value: ${value.javaClass.name}")
        }
        private fun convert(value: Any?, type: Class<*>): Any? {
            if (type == Void.TYPE) return null
            if (value == null) { require(!type.isPrimitive); return null }
            if (value is Number) return when (type) {
                Int::class.java, Int::class.javaObjectType -> value.toInt()
                Long::class.java, Long::class.javaObjectType -> value.toLong()
                Float::class.java, Float::class.javaObjectType -> value.toFloat()
                Double::class.java, Double::class.javaObjectType -> value.toDouble()
                else -> value
            }
            return value
        }
    }
}
