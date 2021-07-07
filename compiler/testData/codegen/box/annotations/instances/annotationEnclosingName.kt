// IGNORE_BACKEND_FIR: JVM_IR
// TARGET_BACKEND: JVM_IR

// WITH_RUNTIME
// !LANGUAGE: +InstantiationOfAnnotationClasses

// FILE: a.kt

package a

annotation class A1

annotation class A2

fun interface I {
    fun run(): A1
}

// FILE: test.kt

import a.*

class E {
    fun insideClass(): A1 = A1()
    fun insideLammbda(): A1 = run { A1() }
    fun insideSAM(): I = I { A1() }
}

class G {
    // test that we can reuse instance in different classes from same file
    fun insideClassAgain(): A1 = A1()
}

fun outsideClass(): A2 = A2()

fun test(instance: Any, parent: String, fqa: String) {
    val clz = instance.javaClass
    assert(clz.getName().startsWith(parent))
    assert(clz.getName().contains(fqa))
    assert(clz.getEnclosingMethod() == null)
    assert(clz.getEnclosingClass().getName() == parent)
    // SAM treated as anonymous because of Origin or something else, see ClassCodegen#IrClass.isAnonymousInnerClass
    // assert(clz.getDeclaringClass() == null)
}

fun box(): String {
    test(E().insideClass(), "E", "a_A1")
    test(E().insideLammbda(), "E", "a_A1")
    test(E().insideSAM().run(), "E", "a_A1")
    test(G().insideClassAgain(), "E", "a_A1")
    test(outsideClass(), "TestKt", "a_A2")
    return "OK"
}