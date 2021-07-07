// IGNORE_BACKEND_FIR: JVM_IR
// TARGET_BACKEND: JVM_IR

// WITH_RUNTIME
// !LANGUAGE: +InstantiationOfAnnotationClasses

// FILE: a.kt

annotation class A(val i: Int)

// FILE: b.kt

inline fun foo(i: Int): A = A(i)

inline fun bar(f: () -> Int): A = A(f())

// FILE: c.kt

class C {
    fun one(): A {
        return foo(1)
    }
}

fun two(): A {
    return bar { 2 }
}

fun box(): String {
    val one = C().one()
    assert(one.i == 1)
    val two = two()
    assert(two.i == 2)
    // Check they're generated on original use site
    assert(one.javaClass.getName().startsWith("BKt"))
    assert(two.javaClass.getName().startsWith("BKt"))
    return "OK"
}