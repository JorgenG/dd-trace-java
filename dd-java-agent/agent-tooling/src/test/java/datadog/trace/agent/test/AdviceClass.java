package datadog.trace.agent.test;

import net.bytebuddy.asm.Advice;

public class AdviceClass {

  /*
  public static void noAdvice() {
    new Unused();
  }
  */

  @Advice.OnMethodEnter
  public static A advice(B b) {
    C c = new C();
    c.d.toString();
    SomeInterface inter = new SomeImplementation();
    inter.someMethod();
    return new A();
  }

  public static class A {}

  public static class B {}

  public static class C {
    public D d = new D();
  }

  public static class D {}

  public static class Unused {}

  public interface SomeInterface {
    void someMethod();
  }

  public static class SomeImplementation implements SomeInterface {
    @Override
    public void someMethod() {}
  }
}
